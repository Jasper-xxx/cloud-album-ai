"""Process-level HTTP and MinIO clients."""

from __future__ import annotations

from threading import Lock

import httpx
import urllib3
from minio import Minio

from app.core.config import get_settings

_lock = Lock()
_http_client: httpx.Client | None = None
_minio_client: Minio | None = None


def get_http_client() -> httpx.Client:
    global _http_client
    if _http_client is None:
        with _lock:
            if _http_client is None:
                settings = get_settings()
                _http_client = httpx.Client(
                    timeout=httpx.Timeout(
                        connect=settings.ai_connect_timeout_seconds,
                        read=settings.ai_request_timeout_seconds,
                        write=settings.ai_request_timeout_seconds,
                        pool=settings.ai_connect_timeout_seconds,
                    ),
                    limits=httpx.Limits(
                        max_connections=settings.ai_http_max_connections,
                        max_keepalive_connections=settings.ai_http_max_keepalive_connections,
                    ),
                    trust_env=True,
                )
    return _http_client


def get_minio_client() -> Minio:
    global _minio_client
    if _minio_client is None:
        with _lock:
            if _minio_client is None:
                settings = get_settings()
                if not settings.minio_configured:
                    raise RuntimeError("MinIO access key and secret key are not configured.")
                _minio_client = Minio(
                    settings.minio_endpoint,
                    access_key=settings.minio_access_key,
                    secret_key=settings.minio_secret_key,
                    secure=settings.minio_secure,
                    http_client=urllib3.PoolManager(
                        timeout=urllib3.Timeout(
                            connect=settings.ai_connect_timeout_seconds,
                            read=settings.ai_request_timeout_seconds,
                        ),
                        maxsize=settings.ai_http_max_connections,
                        retries=False,
                    ),
                )
    return _minio_client


def initialize_clients() -> None:
    get_http_client()


def close_clients() -> None:
    global _http_client, _minio_client
    with _lock:
        if _http_client is not None:
            _http_client.close()
        _http_client = None
        _minio_client = None
