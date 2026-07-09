"""Framework-independent image inference operations."""

from __future__ import annotations

import base64
import binascii
import io
import json
import math
from typing import Any

from PIL import Image, ImageFile, ImageOps, UnidentifiedImageError

from app.clients.runtime import get_http_client, get_minio_client
from app.core.config import get_settings
from app.service_config import Config

ImageFile.LOAD_TRUNCATED_IMAGES = True
settings = get_settings()


class NoFaceDetectedError(ValueError):
    """Raised when a face feature is requested but no face is detected."""


def image_from_bytes(image_bytes: bytes) -> Image.Image:
    if not image_bytes:
        raise ValueError("Uploaded image is empty.")
    try:
        return Image.open(io.BytesIO(image_bytes)).convert("RGB")
    except (UnidentifiedImageError, OSError) as exc:
        raise ValueError("Invalid image payload.") from exc


def image_from_base64(value: str) -> Image.Image:
    if "," in value:
        value = value.split(",", 1)[1]
    try:
        return image_from_bytes(base64.b64decode(value, validate=True))
    except (binascii.Error, ValueError) as exc:
        raise ValueError("Invalid base64 image payload.") from exc


def image_from_url(url: str) -> Image.Image:
    response = get_http_client().get(url)
    response.raise_for_status()
    return image_from_bytes(response.content)


def image_from_minio(object_name: str) -> Image.Image:
    client = get_minio_client()
    response = client.get_object(Config.MINIO_BUCKET, object_name)
    try:
        return image_from_bytes(response.read())
    finally:
        response.close()
        response.release_conn()


def recognize_tags(image: Image.Image) -> list[dict[str, str]]:
    prompt = (
        "识别这张图片的自然标签。不要使用固定分类表。"
        f"最多返回 {Config.TOP_TAGS} 个标签，直接返回严格 JSON 数组，格式为："
        '[{"tagName":"标签","imageType":"类别","confidence":86.0}]。'
        "confidence 为 0 到 100 的数字，不要输出解释。"
    )
    result = _extract_json_object(_call_qwen_vl(prompt, _image_to_data_url(image)))
    if isinstance(result, dict):
        result = result.get("tags", [])
    if not isinstance(result, list):
        raise ValueError("Vision model returned non-list tag payload.")

    tags: list[dict[str, str]] = []
    for item in result[: Config.TOP_TAGS]:
        if not isinstance(item, dict):
            continue
        tag_name = str(item.get("tagName") or item.get("name") or item.get("label") or "").strip()
        image_type = str(item.get("imageType") or item.get("type") or tag_name).strip()
        if not tag_name:
            continue
        confidence = _to_float(item.get("confidence"), 80.0)
        tags.append(
            {
                "tagName": tag_name,
                "imageType": image_type or tag_name,
                "confidence": f"{max(0.0, min(100.0, confidence)):.2f}",
            }
        )
    return tags or [{"tagName": "图片", "imageType": "图片", "confidence": "60.00"}]


def extract_feature(image: Image.Image) -> dict[str, Any]:
    feature = _extract_feature_vector(image)
    return {
        "feature": feature,
        "featureDim": len(feature),
        "featureModel": Config.EMBEDDING_MODEL,
        "featureProvider": Config.FEATURE_PROVIDER,
        "featureVersion": Config.FEATURE_VERSION,
    }


def analyze_faces(image: Image.Image) -> dict[str, Any]:
    faces = _analyze_faces(image)
    return {
        "faces": faces,
        "faceCount": len(faces),
        "featureDim": len(faces[0]["feature"]) if faces else None,
        "featureModel": Config.EMBEDDING_MODEL,
        "featureProvider": Config.FEATURE_PROVIDER,
        "detectProvider": Config.FACE_DETECT_PROVIDER,
    }


def extract_primary_face_feature(image: Image.Image) -> dict[str, Any]:
    faces = _analyze_faces(image)
    if not faces:
        raise NoFaceDetectedError("No face detected.")
    primary = faces[0]
    return {
        "feature": primary["feature"],
        "bbox": primary["bbox"],
        "confidence": primary["confidence"],
        "featureDim": primary["featureDim"],
        "featureModel": primary["featureModel"],
        "featureProvider": primary["featureProvider"],
    }


def health_status() -> dict[str, Any]:
    return {
        "status": "ok" if Config.DASHSCOPE_API_KEY else "missing_dashscope_key",
        "provider": Config.FEATURE_PROVIDER,
        "visionModel": Config.VISION_MODEL,
        "embeddingModel": Config.EMBEDDING_MODEL,
        "embeddingDim": "model_default",
        "featureVersion": Config.FEATURE_VERSION,
        "faceDetectProvider": Config.FACE_DETECT_PROVIDER,
        "dependencies": {
            "dashscopeConfigured": settings.dashscope_configured,
            "minioConfigured": settings.minio_configured,
        },
    }


def _require_dashscope_key() -> str:
    if not Config.DASHSCOPE_API_KEY:
        raise RuntimeError("DASHSCOPE_API_KEY environment variable is not configured.")
    return Config.DASHSCOPE_API_KEY


def _headers() -> dict[str, str]:
    return {
        "Authorization": f"Bearer {_require_dashscope_key()}",
        "Content-Type": "application/json",
    }


def _image_to_bytes(image: Image.Image, fmt: str = "JPEG") -> bytes:
    buffer = io.BytesIO()
    prepared = _prepare_image_for_model(image)
    prepared.save(
        buffer,
        format=fmt,
        quality=Config.IMAGE_JPEG_QUALITY,
        optimize=True,
    )
    return buffer.getvalue()


def _prepare_image_for_model(image: Image.Image) -> Image.Image:
    prepared = ImageOps.exif_transpose(image).convert("RGB")
    width, height = prepared.size
    if width <= 0 or height <= 0:
        raise ValueError("Invalid image dimensions.")

    max_edge_scale = Config.IMAGE_MAX_EDGE / max(width, height)
    max_pixels_scale = math.sqrt(Config.IMAGE_MAX_PIXELS / (width * height))
    scale = min(1.0, max_edge_scale, max_pixels_scale)
    if scale >= 1.0:
        return prepared

    new_size = (
        max(1, int(round(width * scale))),
        max(1, int(round(height * scale))),
    )
    return prepared.resize(new_size, Image.Resampling.LANCZOS)


def _bytes_to_data_url(image_bytes: bytes, mime: str = "image/jpeg") -> str:
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def _image_to_data_url(image: Image.Image) -> str:
    return _bytes_to_data_url(_image_to_bytes(image))


def _extract_json_object(text: str) -> Any:
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:].strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start_candidates = [pos for pos in [text.find("["), text.find("{")] if pos >= 0]
        if not start_candidates:
            raise
        start = min(start_candidates)
        end = max(text.rfind("]"), text.rfind("}"))
        if end <= start:
            raise
        return json.loads(text[start : end + 1])


def _call_qwen_vl(prompt: str, image_data_url: str) -> str:
    payload = {
        "model": Config.VISION_MODEL,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": image_data_url}},
                ],
            }
        ],
        "temperature": 0.1,
    }
    response = get_http_client().post(
        f"{Config.DASHSCOPE_COMPATIBLE_URL}/chat/completions",
        headers=_headers(),
        json=payload,
    )
    response.raise_for_status()
    body = response.json()
    return body["choices"][0]["message"]["content"]


def _embedding_payload(image: Image.Image) -> dict[str, Any]:
    return {
        "model": Config.EMBEDDING_MODEL,
        "input": {"contents": [{"image": _image_to_data_url(image)}]},
    }


def _parse_embedding_response(body: dict[str, Any]) -> list[float]:
    output = body.get("output") or {}
    candidates = [
        output.get("embedding"),
        (
            (output.get("embeddings") or [{}])[0].get("embedding")
            if isinstance(output.get("embeddings"), list)
            else None
        ),
        (
            (body.get("data") or [{}])[0].get("embedding")
            if isinstance(body.get("data"), list)
            else None
        ),
    ]
    for candidate in candidates:
        if isinstance(candidate, list) and candidate:
            return [float(value) for value in candidate]
    raise ValueError(f"Unable to parse embedding response: {body}")


def _extract_feature_vector(image: Image.Image) -> list[float]:
    response = get_http_client().post(
        Config.DASHSCOPE_MULTIMODAL_EMBEDDING_URL,
        headers=_headers(),
        json=_embedding_payload(image),
    )
    response.raise_for_status()
    return _parse_embedding_response(response.json())


def _detect_faces_with_vl(image: Image.Image) -> list[dict[str, Any]]:
    width, height = image.size
    prompt = (
        f"检测图片中的真实人脸。图片宽 {width} 像素，高 {height} 像素。"
        "返回严格 JSON 对象，不要解释："
        '{"faces":[{"bbox":{"x1":10,"y1":20,"x2":100,"y2":120},'
        '"confidence":0.95,"qualityScore":0.90}]}。'
        "bbox 必须使用像素坐标，坐标需要在图片范围内。没有人脸则返回 {\"faces\":[]}。"
    )
    data = _extract_json_object(_call_qwen_vl(prompt, _image_to_data_url(image)))
    face_items = data.get("faces", []) if isinstance(data, dict) else []
    faces: list[dict[str, Any]] = []
    for item in face_items[: Config.FACE_MAX_FACES]:
        bbox = item.get("bbox") if isinstance(item, dict) else None
        if not isinstance(bbox, dict):
            continue
        x1 = _clamp_int(bbox.get("x1"), 0, width - 1)
        y1 = _clamp_int(bbox.get("y1"), 0, height - 1)
        x2 = _clamp_int(bbox.get("x2"), x1 + 1, width)
        y2 = _clamp_int(bbox.get("y2"), y1 + 1, height)
        confidence = _to_float(item.get("confidence"), 0.8)
        if x2 <= x1 or y2 <= y1 or confidence < Config.FACE_MIN_CONFIDENCE:
            continue
        faces.append(
            {
                "bbox": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
                "confidence": confidence,
                "qualityScore": _to_float(item.get("qualityScore"), confidence),
            }
        )
    return faces


def _expand_bbox(bbox: dict[str, int], width: int, height: int) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = bbox["x1"], bbox["y1"], bbox["x2"], bbox["y2"]
    box_width = max(1, x2 - x1)
    box_height = max(1, y2 - y1)
    expand_x = int(round(box_width * Config.FACE_CROP_EXPAND_RATIO))
    expand_y = int(round(box_height * Config.FACE_CROP_EXPAND_RATIO))
    return (
        max(0, x1 - expand_x),
        max(0, y1 - expand_y),
        min(width, x2 + expand_x),
        min(height, y2 + expand_y),
    )


def _analyze_faces(image: Image.Image) -> list[dict[str, Any]]:
    width, height = image.size
    faces = _detect_faces_with_vl(image)
    analyzed: list[dict[str, Any]] = []
    for face in faces:
        crop = image.crop(_expand_bbox(face["bbox"], width, height))
        feature = _extract_feature_vector(crop)
        analyzed.append(
            {
                "bbox": face["bbox"],
                "confidence": round(float(face["confidence"]), 6),
                "qualityScore": round(float(face["qualityScore"]), 6),
                "feature": feature,
                "featureDim": len(feature),
                "featureModel": Config.EMBEDDING_MODEL,
                "featureProvider": Config.FEATURE_PROVIDER,
                "detectProvider": Config.FACE_DETECT_PROVIDER,
            }
        )
    analyzed.sort(key=lambda item: (-item["qualityScore"], -item["confidence"]))
    return analyzed


def _to_float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _clamp_int(value: Any, min_value: int, max_value: int) -> int:
    try:
        number = int(round(float(value)))
    except (TypeError, ValueError):
        number = min_value
    return max(min_value, min(max_value, number))
