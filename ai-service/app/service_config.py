"""Compatibility constants backed by Pydantic settings."""

from app.core.config import get_settings

settings = get_settings()


class Config:
    HOST = settings.ai_service_host
    PORT = settings.ai_service_port
    DEBUG = settings.ai_service_debug

    DASHSCOPE_API_KEY = settings.dashscope_api_key
    DASHSCOPE_COMPATIBLE_URL = settings.dashscope_compatible_url
    DASHSCOPE_MULTIMODAL_EMBEDDING_URL = settings.dashscope_multimodal_embedding_url

    VISION_MODEL = settings.vision_model
    EMBEDDING_MODEL = settings.embedding_model
    FEATURE_PROVIDER = settings.ai_feature_provider
    FEATURE_VERSION = settings.ai_feature_version
    FACE_DETECT_PROVIDER = settings.ai_face_detect_provider

    MINIO_ENDPOINT = settings.minio_endpoint
    MINIO_ACCESS_KEY = settings.minio_access_key
    MINIO_SECRET_KEY = settings.minio_secret_key
    MINIO_BUCKET = settings.minio_bucket
    MINIO_SECURE = settings.minio_secure

    REQUEST_TIMEOUT_SECONDS = settings.ai_request_timeout_seconds
    TOP_TAGS = settings.ai_top_tags
    IMAGE_MAX_EDGE = settings.ai_image_max_edge
    IMAGE_MAX_PIXELS = settings.ai_image_max_pixels
    IMAGE_JPEG_QUALITY = settings.ai_image_jpeg_quality
    FACE_MAX_FACES = settings.ai_face_max_faces
    FACE_MIN_CONFIDENCE = settings.ai_face_min_confidence
    FACE_CROP_EXPAND_RATIO = settings.ai_face_crop_expand_ratio
