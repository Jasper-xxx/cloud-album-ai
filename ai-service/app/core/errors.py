"""Unified FastAPI error responses."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

logger = logging.getLogger(__name__)


class ApiError(Exception):
    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        details: Any = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details


def _request_id(request: Request) -> str:
    return getattr(request.state, "request_id", "unknown")


def _trace_id(request: Request) -> str:
    return getattr(request.state, "trace_id", "unknown")


def _response(
    request: Request,
    status_code: int,
    code: str,
    message: str,
    details: Any = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "code": code,
            "message": message,
            "requestId": _request_id(request),
            "traceId": _trace_id(request),
            "details": details,
        },
    )


def _validation_details(exc: RequestValidationError) -> list[dict[str, Any]]:
    return [
        {
            "location": [str(part) for part in error.get("loc", ())],
            "message": error.get("msg", "Invalid value."),
            "type": error.get("type", "validation_error"),
        }
        for error in exc.errors()
    ]


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
        return _response(request, exc.status_code, exc.code, exc.message, exc.details)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        return _response(
            request,
            422,
            "VALIDATION_ERROR",
            "Invalid request payload.",
            _validation_details(exc),
        )

    @app.exception_handler(Exception)
    async def handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        logger.error(
            "Unhandled AI service error: requestId=%s traceId=%s error=%s",
            _request_id(request),
            _trace_id(request),
            exc,
            exc_info=True,
        )
        return _response(
            request,
            500,
            "INTERNAL_ERROR",
            "The AI service could not process the request.",
        )
