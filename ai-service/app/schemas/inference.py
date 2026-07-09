"""Inference API request and response models."""

from __future__ import annotations

from typing import Any

from pydantic import AnyHttpUrl, BaseModel, Field, model_validator

from app.core.config import get_settings

settings = get_settings()


class ImageJsonRequest(BaseModel):
    image: str | None = Field(default=None, max_length=settings.ai_max_base64_length)
    url: AnyHttpUrl | None = Field(default=None, max_length=settings.ai_max_url_length)
    object_key: str | None = Field(
        default=None,
        min_length=1,
        max_length=settings.ai_max_object_key_length,
    )

    @model_validator(mode="after")
    def require_exactly_one_source(self) -> "ImageJsonRequest":
        source_count = sum(
            value is not None
            for value in (self.image, self.url, self.object_key)
        )
        if source_count != 1:
            raise ValueError("Provide exactly one of image, url, or object_key.")
        return self


class ObjectKeyRequest(BaseModel):
    object_key: str = Field(
        min_length=1,
        max_length=settings.ai_max_object_key_length,
    )


class TagResponse(BaseModel):
    tagName: str
    imageType: str
    confidence: str


class FeatureResponse(BaseModel):
    feature: list[float]
    featureDim: int
    featureModel: str
    featureProvider: str
    featureVersion: str


class BoundingBox(BaseModel):
    x1: int
    y1: int
    x2: int
    y2: int


class FaceItem(BaseModel):
    bbox: BoundingBox
    confidence: float
    qualityScore: float
    feature: list[float]
    featureDim: int
    featureModel: str
    featureProvider: str
    detectProvider: str


class FaceAnalyzeResponse(BaseModel):
    faces: list[FaceItem]
    faceCount: int
    featureDim: int | None
    featureModel: str
    featureProvider: str
    detectProvider: str


class FaceFeatureResponse(BaseModel):
    feature: list[float]
    bbox: BoundingBox
    confidence: float
    featureDim: int
    featureModel: str
    featureProvider: str


class HealthResponse(BaseModel):
    status: str
    provider: str
    visionModel: str
    embeddingModel: str
    embeddingDim: str
    featureVersion: str
    faceDetectProvider: str
    dependencies: dict[str, Any] | None = None


class ErrorResponse(BaseModel):
    code: str
    message: str
    requestId: str
    traceId: str
    details: Any = None
