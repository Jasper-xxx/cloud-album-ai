"""FastAPI entry point for the Cloud Album AI service."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes.inference import router
from app.clients.runtime import close_clients, initialize_clients
from app.core.config import get_settings
from app.core.errors import register_exception_handlers
from app.core.observability import (
    generate_id,
    reset_trace_context,
    set_trace_context,
    setup_logging,
    trace_id_from_traceparent,
)

settings = get_settings()
setup_logging(settings.ai_service_debug)


@asynccontextmanager
async def lifespan(app: FastAPI):
    initialize_clients()
    app.state.ai_semaphore = asyncio.Semaphore(settings.ai_max_concurrency)
    yield
    close_clients()


app = FastAPI(
    title="Cloud Album AI Service",
    version="1.0.0",
    description="Image tagging, embedding, and face analysis APIs.",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID", "X-Trace-ID", "traceparent"],
)


@app.middleware("http")
async def add_request_id(request: Request, call_next):
    request_id = request.headers.get("X-Request-ID", "").strip() or generate_id()
    trace_id = (
        request.headers.get("X-Trace-ID", "").strip()
        or trace_id_from_traceparent(request.headers.get("traceparent"))
        or request_id
    )
    tokens = set_trace_context(request_id, trace_id)
    request.state.request_id = request_id
    request.state.trace_id = trace_id
    try:
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        response.headers["X-Trace-ID"] = trace_id
        return response
    finally:
        reset_trace_context(tokens)


register_exception_handlers(app)
app.include_router(router)
