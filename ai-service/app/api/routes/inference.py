"""FastAPI routes compatible with the legacy AI service endpoints."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass
from typing import Any, Callable

import httpx
from fastapi import APIRouter, Request
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile

from app.core.config import get_settings
from app.core.errors import ApiError
from app.schemas.inference import (
    ErrorResponse,
    FaceAnalyzeResponse,
    FaceFeatureResponse,
    FeatureResponse,
    HealthResponse,
    ImageJsonRequest,
    ObjectKeyRequest,
    TagResponse,
)
from app.services import inference

router = APIRouter()
settings = get_settings()
logger = logging.getLogger(__name__)

ERROR_RESPONSES = {
    400: {"model": ErrorResponse},
    404: {"model": ErrorResponse},
    413: {"model": ErrorResponse},
    415: {"model": ErrorResponse},
    422: {"model": ErrorResponse},
    500: {"model": ErrorResponse},
    502: {"model": ErrorResponse},
    503: {"model": ErrorResponse},
}

IMAGE_REQUEST_BODY = {
    "requestBody": {
        "required": True,
        "content": {
            "application/json": {
                "schema": {
                    "type": "object",
                    "properties": {
                        "image": {"type": "string", "description": "Base64 image"},
                        "url": {"type": "string", "format": "uri"},
                        "object_key": {"type": "string"},
                    },
                    "description": "Provide exactly one image source.",
                }
            },
            "multipart/form-data": {
                "schema": {
                    "type": "object",
                    "required": ["image"],
                    "properties": {
                        "image": {"type": "string", "format": "binary"},
                    },
                }
            },
        },
    }
}


@dataclass(frozen=True)
class ImageSource:
    kind: str
    value: str | bytes


def _validation_details(exc: ValidationError) -> list[dict[str, Any]]:
    return [
        {
            "location": [str(part) for part in error.get("loc", ())],
            "message": error.get("msg", "Invalid value."),
            "type": error.get("type", "validation_error"),
        }
        for error in exc.errors()
    ]


async def _parse_image_source(request: Request) -> ImageSource:
    content_type = request.headers.get("content-type", "").lower()
    if content_type.startswith("multipart/form-data"):
        form = await request.form()
        upload = form.get("image")
        if not isinstance(upload, UploadFile):
            raise ApiError(400, "MISSING_IMAGE", "Missing image payload.")
        image_bytes = await upload.read(settings.ai_max_upload_bytes + 1)
        if not image_bytes:
            raise ApiError(400, "EMPTY_IMAGE", "Uploaded image is empty.")
        if len(image_bytes) > settings.ai_max_upload_bytes:
            raise ApiError(
                413,
                "IMAGE_TOO_LARGE",
                f"Uploaded image exceeds {settings.ai_max_upload_bytes} bytes.",
            )
        return ImageSource("bytes", image_bytes)

    if content_type.startswith("application/json"):
        try:
            payload = ImageJsonRequest.model_validate(await request.json())
        except json.JSONDecodeError as exc:
            raise ApiError(400, "INVALID_JSON", "Request body is not valid JSON.") from exc
        except ValidationError as exc:
            raise ApiError(
                422,
                "VALIDATION_ERROR",
                "Invalid request payload.",
                _validation_details(exc),
            ) from exc
        if payload.image is not None:
            return ImageSource("base64", payload.image)
        if payload.url is not None:
            return ImageSource("url", str(payload.url))
        return ImageSource("object_key", payload.object_key or "")

    raise ApiError(
        415,
        "UNSUPPORTED_MEDIA_TYPE",
        "Use application/json or multipart/form-data.",
    )


def _load_image(source: ImageSource):
    if source.kind == "bytes":
        return inference.image_from_bytes(source.value)
    if source.kind == "base64":
        return inference.image_from_base64(str(source.value))
    if source.kind == "url":
        return inference.image_from_url(str(source.value))
    return inference.image_from_minio(str(source.value))


async def _run_limited(
    request: Request,
    operation: Callable[..., Any],
    *args: Any,
) -> Any:
    semaphore: asyncio.Semaphore = request.app.state.ai_semaphore
    queued_at = time.perf_counter()
    try:
        await asyncio.wait_for(
            semaphore.acquire(),
            timeout=settings.ai_concurrency_wait_seconds,
        )
    except asyncio.TimeoutError as exc:
        raise ApiError(
            503,
            "AI_BUSY",
            "The AI service is at its concurrency limit. Please retry later.",
        ) from exc

    try:
        started_at = time.perf_counter()
        result = await run_in_threadpool(operation, *args)
        logger.info(
            "AI request completed: path=%s requestId=%s traceId=%s queueMs=%.1f executionMs=%.1f",
            request.url.path,
            getattr(request.state, "request_id", "-"),
            getattr(request.state, "trace_id", "-"),
            (started_at - queued_at) * 1000,
            (time.perf_counter() - started_at) * 1000,
        )
        return result
    except inference.NoFaceDetectedError as exc:
        raise ApiError(404, "NO_FACE_DETECTED", str(exc)) from exc
    except ValueError as exc:
        raise ApiError(400, "INVALID_IMAGE", str(exc)) from exc
    except httpx.HTTPError as exc:
        raise ApiError(
            502,
            "EXTERNAL_SERVICE_ERROR",
            "An external image or AI service request failed.",
        ) from exc
    except RuntimeError as exc:
        raise ApiError(503, "SERVICE_NOT_CONFIGURED", str(exc)) from exc
    finally:
        semaphore.release()


def _recognize(source: ImageSource) -> list[dict[str, str]]:
    return inference.recognize_tags(_load_image(source))


def _extract_feature(source: ImageSource) -> dict[str, Any]:
    return inference.extract_feature(_load_image(source))


def _analyze_faces(source: ImageSource) -> dict[str, Any]:
    return inference.analyze_faces(_load_image(source))


def _extract_face_feature(source: ImageSource) -> dict[str, Any]:
    return inference.extract_primary_face_feature(_load_image(source))


@router.post(
    "/recognize",
    response_model=list[TagResponse],
    responses=ERROR_RESPONSES,
    openapi_extra=IMAGE_REQUEST_BODY,
)
async def recognize(request: Request) -> list[dict[str, str]]:
    return await _run_limited(request, _recognize, await _parse_image_source(request))


@router.post(
    "/recognize_from_minio",
    response_model=list[TagResponse],
    responses=ERROR_RESPONSES,
)
async def recognize_from_minio(
    request: Request,
    payload: ObjectKeyRequest,
) -> list[dict[str, str]]:
    return await _run_limited(
        request,
        _recognize,
        ImageSource("object_key", payload.object_key),
    )


@router.post(
    "/extract_feature",
    response_model=FeatureResponse,
    responses=ERROR_RESPONSES,
    openapi_extra=IMAGE_REQUEST_BODY,
)
async def extract_feature(request: Request) -> dict[str, Any]:
    return await _run_limited(
        request,
        _extract_feature,
        await _parse_image_source(request),
    )


@router.post(
    "/face_analyze",
    response_model=FaceAnalyzeResponse,
    responses=ERROR_RESPONSES,
    openapi_extra=IMAGE_REQUEST_BODY,
)
async def face_analyze(request: Request) -> dict[str, Any]:
    return await _run_limited(
        request,
        _analyze_faces,
        await _parse_image_source(request),
    )


@router.post(
    "/face_feature",
    response_model=FaceFeatureResponse,
    responses=ERROR_RESPONSES,
    openapi_extra=IMAGE_REQUEST_BODY,
)
async def face_feature(request: Request) -> dict[str, Any]:
    return await _run_limited(
        request,
        _extract_face_feature,
        await _parse_image_source(request),
    )


@router.get("/health", response_model=HealthResponse)
async def health() -> dict[str, Any]:
    return inference.health_status()
