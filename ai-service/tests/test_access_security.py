import io
import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch, Mock

os.environ["AI_SERVICE_KEY"] = "test-service-key-" + "a" * 32

from fastapi.testclient import TestClient
from PIL import Image
from app.main import app
from app.core.config import get_settings
from app.core.budget import reserve_model_call
from app.core.errors import ApiError
from app.services import inference


class AccessSecurityTest(unittest.TestCase):
    def test_anonymous_and_forged_requests_never_read_objects(self):
        with TestClient(app) as client, patch.object(inference, "image_from_minio") as read:
            for headers in ({}, {"X-AI-Service-Key": "forged"}):
                self.assertEqual(401, client.post("/recognize_from_minio", json={"object_key": "secret"}, headers=headers).status_code)
            read.assert_not_called()

    def test_url_never_issues_network_request(self):
        with patch.object(inference, "get_http_client") as client:
            for url in ("http://127.0.0.1/admin", "http://169.254.169.254/", "https://example.com/a.png"):
                with self.assertRaises(ValueError):
                    inference.image_from_url(url)
            client.assert_not_called()

    def test_pixel_limit_precedes_decode(self):
        buffer = io.BytesIO()
        Image.new("RGB", (20, 20)).save(buffer, "PNG")
        with patch.object(inference.settings, "ai_decode_max_pixels", 100):
            with self.assertRaisesRegex(ValueError, "pixel limit"):
                inference.image_from_bytes(buffer.getvalue())

    def test_object_read_is_bounded_and_connection_closed(self):
        response = Mock()
        response.read.return_value = b"x" * 101
        with patch.object(inference, "get_minio_client") as client, patch.object(inference.settings, "ai_max_upload_bytes", 100):
            client.return_value.get_object.return_value = response
            with self.assertRaisesRegex(ValueError, "byte limit"):
                inference.image_from_minio("authorized-object")
        response.read.assert_called_once_with(101)
        response.close.assert_called_once()
        response.release_conn.assert_called_once()

    def test_budget_is_atomic_and_survives_new_connections(self):
        settings = get_settings()
        with tempfile.TemporaryDirectory() as directory, patch.object(settings, "ai_budget_database", directory + "/budget.db"), patch.object(settings, "ai_daily_model_call_limit", 5):
            def attempt(_):
                try:
                    reserve_model_call()
                    return True
                except ApiError as error:
                    self.assertEqual(429, error.status_code)
                    return False
            with ThreadPoolExecutor(max_workers=8) as pool:
                self.assertEqual(5, sum(pool.map(attempt, range(20))))
            self.assertFalse(attempt(None))
