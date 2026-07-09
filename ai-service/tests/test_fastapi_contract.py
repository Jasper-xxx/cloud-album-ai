"""Contract tests for the FastAPI adapter."""

from __future__ import annotations

import asyncio
import base64
import io
import os
import unittest
from unittest.mock import patch

import httpx
from PIL import Image

os.environ.setdefault("DASHSCOPE_API_KEY", "test-key")
os.environ.setdefault("MINIO_ACCESS_KEY", "test-access")
os.environ.setdefault("MINIO_SECRET_KEY", "test-secret")

from fastapi.testclient import TestClient  # noqa: E402
from pydantic import ValidationError  # noqa: E402

from app.api.routes import inference as inference_routes  # noqa: E402
from app.core.config import Settings  # noqa: E402
from app.main import app  # noqa: E402
from app.services import inference  # noqa: E402


def _image_bytes() -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (4, 4), color="white").save(buffer, format="PNG")
    return buffer.getvalue()


class FastApiContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client_context = TestClient(app)
        self.client = self.client_context.__enter__()

    def tearDown(self) -> None:
        self.client_context.__exit__(None, None, None)

    @patch.object(inference, "recognize_tags")
    def test_recognize_accepts_base64_json_and_matches_legacy_success_shape(
        self,
        recognize_tags,
    ) -> None:
        expected = [{"tagName": "海边", "imageType": "风景", "confidence": "98.50"}]
        recognize_tags.return_value = expected

        response = self.client.post(
            "/recognize",
            json={"image": base64.b64encode(_image_bytes()).decode("ascii")},
        )

        self.assertEqual(200, response.status_code)
        self.assertEqual(expected, response.json())

    def test_recognize_validation_error_uses_unified_shape(self) -> None:
        response = self.client.post("/recognize", json={})
        body = response.json()

        self.assertEqual(422, response.status_code)
        self.assertEqual("VALIDATION_ERROR", body["code"])
        self.assertEqual("Invalid request payload.", body["message"])
        self.assertTrue(body["requestId"])
        self.assertIsInstance(body["details"], list)

    def test_invalid_base64_image_returns_400(self) -> None:
        response = self.client.post("/recognize", json={"image": "not-base64!"})

        self.assertEqual(400, response.status_code)
        self.assertEqual("INVALID_IMAGE", response.json()["code"])

    def test_model_payload_images_are_downscaled_before_encoding(self) -> None:
        original_max_edge = inference.Config.IMAGE_MAX_EDGE
        original_max_pixels = inference.Config.IMAGE_MAX_PIXELS
        original_quality = inference.Config.IMAGE_JPEG_QUALITY
        inference.Config.IMAGE_MAX_EDGE = 128
        inference.Config.IMAGE_MAX_PIXELS = 10_000
        inference.Config.IMAGE_JPEG_QUALITY = 80
        try:
            data_url = inference._image_to_data_url(Image.new("RGB", (3000, 1000)))
            encoded = data_url.split(",", 1)[1]
            decoded = Image.open(io.BytesIO(base64.b64decode(encoded)))

            self.assertLessEqual(max(decoded.size), 128)
            self.assertLessEqual(decoded.size[0] * decoded.size[1], 10_000)
        finally:
            inference.Config.IMAGE_MAX_EDGE = original_max_edge
            inference.Config.IMAGE_MAX_PIXELS = original_max_pixels
            inference.Config.IMAGE_JPEG_QUALITY = original_quality

    @patch.object(inference, "image_from_url")
    def test_external_image_timeout_returns_502(self, image_from_url) -> None:
        image_from_url.side_effect = httpx.ReadTimeout("timed out")

        response = self.client.post(
            "/recognize",
            json={"url": "https://example.com/image.jpg"},
        )

        self.assertEqual(502, response.status_code)
        self.assertEqual("EXTERNAL_SERVICE_ERROR", response.json()["code"])

    @patch.object(inference, "recognize_tags")
    def test_unexpected_failure_hides_internal_details(self, recognize_tags) -> None:
        recognize_tags.side_effect = Exception("sensitive upstream detail")
        with TestClient(app, raise_server_exceptions=False) as client:
            response = client.post(
                "/recognize",
                json={"image": base64.b64encode(_image_bytes()).decode("ascii")},
            )

        self.assertEqual(500, response.status_code)
        self.assertEqual("INTERNAL_ERROR", response.json()["code"])
        self.assertNotIn("sensitive upstream detail", response.text)

    @patch.object(inference, "recognize_tags")
    @patch.object(inference, "image_from_minio")
    def test_recognize_from_minio_matches_legacy_success_shape(
        self,
        image_from_minio,
        recognize_tags,
    ) -> None:
        expected = [{"tagName": "人物", "imageType": "人物", "confidence": "90.00"}]
        image_from_minio.return_value = Image.new("RGB", (4, 4))
        recognize_tags.return_value = expected

        response = self.client.post(
            "/recognize_from_minio",
            json={"object_key": "user/a.jpg"},
        )

        self.assertEqual(200, response.status_code)
        self.assertEqual(expected, response.json())
        image_from_minio.assert_called_once_with("user/a.jpg")

    @patch.object(inference, "extract_feature")
    def test_extract_feature_accepts_multipart_and_matches_legacy_fields(
        self,
        extract_feature,
    ) -> None:
        expected = {
            "feature": [0.1, 0.2],
            "featureDim": 2,
            "featureModel": "test-model",
            "featureProvider": "aliyun",
            "featureVersion": "v1",
        }
        extract_feature.return_value = expected

        response = self.client.post(
            "/extract_feature",
            files={"image": ("query.png", _image_bytes(), "image/png")},
        )

        self.assertEqual(200, response.status_code)
        self.assertEqual(expected, response.json())

    @patch.object(inference, "analyze_faces")
    def test_face_analyze_matches_legacy_fields(self, analyze_faces) -> None:
        expected = {
            "faces": [],
            "faceCount": 0,
            "featureDim": None,
            "featureModel": "test-model",
            "featureProvider": "aliyun",
            "detectProvider": "test-detector",
        }
        analyze_faces.return_value = expected

        response = self.client.post(
            "/face_analyze",
            files={"image": ("face.png", _image_bytes(), "image/png")},
        )

        self.assertEqual(200, response.status_code)
        self.assertEqual(expected, response.json())

    @patch.object(inference, "extract_primary_face_feature")
    def test_face_feature_matches_legacy_fields(self, extract_primary_face_feature) -> None:
        expected = {
            "feature": [0.5, 0.6],
            "bbox": {"x1": 1, "y1": 2, "x2": 3, "y2": 4},
            "confidence": 0.95,
            "featureDim": 2,
            "featureModel": "test-model",
            "featureProvider": "aliyun",
        }
        extract_primary_face_feature.return_value = expected

        response = self.client.post(
            "/face_feature",
            files={"image": ("face.png", _image_bytes(), "image/png")},
        )

        self.assertEqual(200, response.status_code)
        self.assertEqual(expected, response.json())

    @patch.object(inference, "extract_primary_face_feature")
    def test_face_feature_without_face_uses_unified_404(
        self,
        extract_primary_face_feature,
    ) -> None:
        extract_primary_face_feature.side_effect = inference.NoFaceDetectedError(
            "No face detected."
        )

        response = self.client.post(
            "/face_feature",
            files={"image": ("face.png", _image_bytes(), "image/png")},
        )

        self.assertEqual(404, response.status_code)
        self.assertEqual("NO_FACE_DETECTED", response.json()["code"])

    def test_health_keeps_legacy_fields_and_adds_dependency_state(self) -> None:
        response = self.client.get("/health")
        body = response.json()

        self.assertEqual(200, response.status_code)
        self.assertEqual("ok", body["status"])
        self.assertIn("provider", body)
        self.assertIn("visionModel", body)
        self.assertIn("embeddingModel", body)
        self.assertIn("featureVersion", body)
        self.assertIn("faceDetectProvider", body)
        self.assertIn("dependencies", body)

    def test_request_id_is_echoed_in_response_header(self) -> None:
        response = self.client.get(
            "/health",
            headers={"X-Request-ID": "contract-test-request"},
        )

        self.assertEqual("contract-test-request", response.headers["X-Request-ID"])

    def test_concurrency_saturation_returns_503(self) -> None:
        original_wait = inference_routes.settings.ai_concurrency_wait_seconds
        inference_routes.settings.ai_concurrency_wait_seconds = 0.01
        app.state.ai_semaphore = asyncio.Semaphore(0)
        try:
            response = self.client.post(
                "/recognize",
                json={"image": base64.b64encode(_image_bytes()).decode("ascii")},
            )
        finally:
            inference_routes.settings.ai_concurrency_wait_seconds = original_wait

        self.assertEqual(503, response.status_code)
        self.assertEqual("AI_BUSY", response.json()["code"])

    def test_openapi_contains_all_legacy_routes(self) -> None:
        response = self.client.get("/openapi.json")
        paths = response.json()["paths"]

        self.assertEqual(200, response.status_code)
        for path in (
            "/recognize",
            "/recognize_from_minio",
            "/extract_feature",
            "/face_analyze",
            "/face_feature",
            "/health",
        ):
            self.assertIn(path, paths)

    def test_docs_page_is_available(self) -> None:
        response = self.client.get("/docs")

        self.assertEqual(200, response.status_code)
        self.assertIn("Cloud Album AI Service", response.text)

    def test_cors_preflight_allows_configured_development_origin(self) -> None:
        response = self.client.options(
            "/recognize",
            headers={
                "Origin": "http://localhost:5173",
                "Access-Control-Request-Method": "POST",
            },
        )

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            "http://localhost:5173",
            response.headers["access-control-allow-origin"],
        )

    def test_wildcard_cors_configuration_is_rejected(self) -> None:
        with self.assertRaises(ValidationError):
            Settings(ai_service_cors_allowed_origins="*")


if __name__ == "__main__":
    unittest.main()
