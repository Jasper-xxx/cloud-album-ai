"""Authenticate before reading request bodies; bound streamed/chunked payloads too."""
import hmac
import asyncio

from starlette.responses import JSONResponse
from app.core.config import get_settings


class ServiceAccessMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] == "/health":
            return await self.app(scope, receive, send)
        settings = get_settings()
        headers = dict(scope.get("headers", []))
        supplied = headers.get(b"x-ai-service-key", b"")
        configured = settings.ai_service_key.encode()
        if len(configured) < 32 or not hmac.compare_digest(supplied, configured):
            return await JSONResponse({"code": "UNAUTHORIZED", "message": "Valid service credentials required."},
                                      status_code=401)(scope, receive, send)
        admission = scope["app"].state.ai_admission
        try:
            await asyncio.wait_for(admission.acquire(), settings.ai_concurrency_wait_seconds)
        except asyncio.TimeoutError:
            return await JSONResponse({"code": "AI_BUSY", "message": "The instance is at its concurrency limit."},
                                      status_code=503)(scope, receive, send)
        try:
            return await self.authorized(scope, receive, send, settings)
        finally:
            admission.release()

    async def authorized(self, scope, receive, send, settings):
        limit = max(settings.ai_max_base64_length, settings.ai_max_upload_bytes) + 65536
        # Buffer only up to the hard limit, before JSON/multipart parsers allocate memory/disk.
        chunks, size = [], 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            size += len(message.get("body", b""))
            if size > limit:
                return await JSONResponse({"code": "IMAGE_TOO_LARGE", "message": "Request body too large."},
                                          status_code=413)(scope, receive, send)
            chunks.append(message)
            if not message.get("more_body", False):
                break
        index = 0

        async def bounded_receive():
            nonlocal index
            if index < len(chunks):
                message = chunks[index]
                index += 1
                return message
            return await receive()

        await self.app(scope, bounded_receive, send)
