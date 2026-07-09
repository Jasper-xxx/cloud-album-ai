"""Request tracing and JSON logging for the AI service."""

from __future__ import annotations

import contextvars
import json
import logging
import sys
from datetime import datetime, timezone
from uuid import uuid4

request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="-")
trace_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("trace_id", default="-")


class JsonLogFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "requestId": getattr(record, "request_id", request_id_var.get()),
            "traceId": getattr(record, "trace_id", trace_id_var.get()),
        }
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


class TraceContextFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get()
        record.trace_id = trace_id_var.get()
        return True


def setup_logging(debug: bool = False) -> None:
    root = logging.getLogger()
    root.handlers.clear()
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonLogFormatter())
    handler.addFilter(TraceContextFilter())
    root.addHandler(handler)
    root.setLevel(logging.DEBUG if debug else logging.INFO)


def generate_id() -> str:
    return uuid4().hex


def trace_id_from_traceparent(traceparent: str | None) -> str | None:
    if not traceparent:
        return None
    parts = traceparent.strip().split("-")
    if len(parts) >= 2 and len(parts[1]) == 32:
        trace_id = parts[1].lower()
        if all(char in "0123456789abcdef" for char in trace_id):
            return trace_id
    return None


def set_trace_context(request_id: str, trace_id: str) -> tuple[contextvars.Token[str], contextvars.Token[str]]:
    return request_id_var.set(request_id), trace_id_var.set(trace_id)


def reset_trace_context(tokens: tuple[contextvars.Token[str], contextvars.Token[str]]) -> None:
    request_token, trace_token = tokens
    request_id_var.reset(request_token)
    trace_id_var.reset(trace_token)
