"""Environment-backed settings for the AI service."""

from __future__ import annotations

import os
from functools import lru_cache

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    ai_service_host: str = "0.0.0.0"
    ai_service_port: int = Field(default=5000, ge=1, le=65535)
    ai_service_debug: bool = False
    ai_service_cors_allowed_origins: str = (
        "http://localhost:5173,http://127.0.0.1:5173,"
        "http://localhost:8080,http://127.0.0.1:8080"
    )

    dashscope_api_key: str = ""
    dashscope_compatible_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    dashscope_multimodal_embedding_url: str = (
        "https://dashscope.aliyuncs.com/api/v1/services/embeddings/"
        "multimodal-embedding/multimodal-embedding"
    )

    vision_model: str = "qwen3-vl-flash"
    embedding_model: str = "qwen3-vl-embedding"
    ai_feature_provider: str = "aliyun"
    ai_feature_version: str = "v1"
    ai_face_detect_provider: str = "aliyun-qwen-vl-face-detect"

    minio_endpoint: str = "127.0.0.1:9000"
    minio_access_key: str = ""
    minio_secret_key: str = ""
    minio_bucket: str = "pictures"
    minio_secure: bool = False

    ai_request_timeout_seconds: float = Field(default=60.0, gt=0)
    ai_connect_timeout_seconds: float = Field(default=10.0, gt=0)
    ai_http_max_connections: int = Field(default=20, ge=1)
    ai_http_max_keepalive_connections: int = Field(default=10, ge=1)
    ai_max_concurrency: int = Field(default=4, ge=1)
    ai_concurrency_wait_seconds: float = Field(default=5.0, gt=0)

    ai_top_tags: int = Field(default=8, ge=1, le=50)
    ai_image_max_edge: int = Field(default=1280, ge=128, le=4096)
    ai_image_max_pixels: int = Field(default=1_200_000, ge=10_000)
    ai_image_jpeg_quality: int = Field(default=82, ge=40, le=95)
    ai_face_max_faces: int = Field(default=10, ge=1, le=100)
    ai_face_min_confidence: float = Field(default=0.25, ge=0, le=1)
    ai_face_crop_expand_ratio: float = Field(default=0.18, ge=0, le=1)

    ai_max_upload_bytes: int = Field(default=50 * 1024 * 1024, ge=1)
    ai_max_base64_length: int = Field(default=70 * 1024 * 1024, ge=1)
    ai_max_url_length: int = Field(default=2048, ge=1)
    ai_max_object_key_length: int = Field(default=1024, ge=1)

    @field_validator("ai_service_cors_allowed_origins")
    @classmethod
    def validate_cors_origins(cls, value: str) -> str:
        origins = [origin.strip() for origin in value.split(",") if origin.strip()]
        if not origins:
            raise ValueError("AI_SERVICE_CORS_ALLOWED_ORIGINS cannot be empty.")
        if "*" in origins:
            raise ValueError("Wildcard CORS origins are not allowed.")
        return ",".join(origins)

    @property
    def cors_allowed_origins(self) -> list[str]:
        return [
            origin.strip()
            for origin in self.ai_service_cors_allowed_origins.split(",")
            if origin.strip()
        ]

    @property
    def dashscope_configured(self) -> bool:
        return bool(self.dashscope_api_key)

    @property
    def minio_configured(self) -> bool:
        return bool(self.minio_access_key and self.minio_secret_key)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    settings = Settings()
    current_no_proxy = os.environ.get("NO_PROXY", "localhost,127.0.0.1,::1")
    if ".aliyuncs.com" not in current_no_proxy:
        current_no_proxy += ",.aliyuncs.com"
    os.environ["NO_PROXY"] = current_no_proxy
    os.environ["no_proxy"] = current_no_proxy
    return settings
