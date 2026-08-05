from __future__ import annotations

import json
import mimetypes
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .metrics import EvaluationError


def _http_request(
    url: str,
    method: str,
    headers: dict[str, str],
    body: bytes | None,
    timeout_seconds: float,
) -> tuple[int, Any]:
    request = urllib.request.Request(url=url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            status = response.status
            raw = response.read()
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read()
    try:
        payload = json.loads(raw.decode("utf-8")) if raw else None
    except (UnicodeDecodeError, json.JSONDecodeError):
        payload = None
    return status, payload


def _multipart(fields: dict[str, Any], file_path: Path) -> tuple[bytes, str]:
    boundary = f"----cloud-album-eval-{uuid.uuid4().hex}"
    chunks: list[bytes] = []
    for key, raw_value in fields.items():
        values = raw_value if isinstance(raw_value, list) else [raw_value]
        for value in values:
            if value is None:
                continue
            chunks.extend(
                [
                    f"--{boundary}\r\n".encode(),
                    f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode(),
                    str(value).encode("utf-8"),
                    b"\r\n",
                ]
            )
    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    chunks.extend(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="image"; filename="{file_path.name}"\r\n'.encode(),
            f"Content-Type: {content_type}\r\n\r\n".encode(),
            file_path.read_bytes(),
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def collect_image_search(
    gold_records: list[dict[str, Any]],
    dataset_path: Path,
    base_url: str,
    token: str,
    timeout_seconds: float,
    delay_ms: int,
    repeats: int,
) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    endpoint = urllib.parse.urljoin(base_url.rstrip("/") + "/", "imageSearch/search")
    for record_index, gold in enumerate(gold_records):
        query_value = gold.get("query_path")
        if not query_value:
            raise EvaluationError("image search gold record is missing query_path")
        query_path = Path(str(query_value))
        if not query_path.is_absolute():
            query_path = dataset_path.parent / query_path
        query_path = query_path.resolve()
        if not query_path.is_file():
            raise EvaluationError(f"query image does not exist: {query_path}")
        fields = {
            "mode": gold.get("mode", "fuzzy"),
            "albumIds": gold.get("album_ids"),
            "tagNames": gold.get("tag_names"),
            "sizeRange": gold.get("size_range"),
        }
        for repeat in range(repeats):
            body, content_type = _multipart(fields, query_path)
            started = time.perf_counter()
            try:
                status, payload = _http_request(
                    endpoint,
                    "POST",
                    {
                        "Accept": "application/json",
                        "Authorization": token,
                        "Content-Type": content_type,
                        "X-Request-ID": f"eval-image-{gold['case_id']}-{repeat + 1}",
                    },
                    body,
                    timeout_seconds,
                )
                latency_ms = (time.perf_counter() - started) * 1000
                data = payload.get("data") if isinstance(payload, dict) else None
                returned_ids = [
                    item.get("fileId")
                    for item in data
                    if isinstance(item, dict) and item.get("fileId") is not None
                ] if isinstance(data, list) else []
                error = None if 200 <= status < 300 and isinstance(data, list) else f"HTTP {status}"
            except Exception as exc:  # Collector must preserve failed queries as recall=0.
                latency_ms = (time.perf_counter() - started) * 1000
                returned_ids = []
                status = None
                error = f"{type(exc).__name__}: {exc}"
            result = {
                "case_id": (
                    str(gold["case_id"])
                    if repeats == 1
                    else f"{gold['case_id']}#r{repeat + 1}"
                ),
                "source_case_id": str(gold["case_id"]),
                "relevant_ids": gold.get("relevant_ids") or [],
                "returned_ids": returned_ids,
                "latency_ms": round(latency_ms, 3),
                "http_status": status,
                "error": error,
            }
            output.append(result)
            is_last = record_index == len(gold_records) - 1 and repeat == repeats - 1
            if delay_ms > 0 and not is_last:
                time.sleep(delay_ms / 1000)
    return output


ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _expand_env(value: Any) -> Any:
    if isinstance(value, str):
        def replace(match: re.Match[str]) -> str:
            name = match.group(1)
            if name not in os.environ:
                raise EvaluationError(f"missing environment variable: {name}")
            return os.environ[name]
        return ENV_PATTERN.sub(replace, value)
    if isinstance(value, list):
        return [_expand_env(item) for item in value]
    if isinstance(value, dict):
        return {key: _expand_env(item) for key, item in value.items()}
    return value


def _json_path_lookup(payload: Any, path: str) -> tuple[bool, Any]:
    if path in {"", "$"}:
        return True, payload
    current = payload
    for part in path.removeprefix("$.").split("."):
        if not part:
            continue
        if not isinstance(current, dict) or part not in current:
            return False, None
        current = current[part]
    return True, current


def _json_path(payload: Any, path: str) -> Any:
    return _json_path_lookup(payload, path)[1]


def _is_blocked(status: int, payload: Any, condition: dict[str, Any]) -> bool:
    if status in condition.get("http_status_in", []):
        return True
    json_path = condition.get("json_path")
    if json_path:
        actual = _json_path(payload, str(json_path))
        if actual in condition.get("value_in", []):
            return True
    return False


def _security_request(
    base_url: str,
    request_config: dict[str, Any],
    timeout_seconds: float,
    request_id: str,
) -> tuple[int, Any]:
    method = str(request_config.get("method", "GET")).upper()
    path = str(request_config.get("path", ""))
    if not path:
        raise EvaluationError(f"{request_id}: request.path is required")
    url = urllib.parse.urljoin(base_url.rstrip("/") + "/", path.lstrip("/"))
    headers = {
        str(key): str(value)
        for key, value in (request_config.get("headers") or {}).items()
    }
    headers.setdefault("Accept", "application/json")
    headers["X-Request-ID"] = request_id
    json_body = request_config.get("json")
    body = None
    if json_body is not None:
        body = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        headers.setdefault("Content-Type", "application/json")
    return _http_request(url, method, headers, body, timeout_seconds)


def _side_effect_is_safe(
    before_status: int,
    before_payload: Any,
    after_status: int,
    after_payload: Any,
    config: dict[str, Any],
) -> bool:
    accepted_statuses = config.get("http_status_in", [200])
    if (
        before_status not in accepted_statuses
        or after_status not in accepted_statuses
    ):
        return False
    json_path = str(config.get("json_path", "$"))
    before_present, before_value = _json_path_lookup(before_payload, json_path)
    after_present, after_value = _json_path_lookup(after_payload, json_path)
    if not before_present or not after_present:
        return False
    comparison = str(config.get("comparison", "unchanged"))
    if comparison == "unchanged":
        return before_value == after_value
    if comparison == "equals":
        return after_value == config.get("expected")
    raise EvaluationError(
        "side_effect_check.comparison must be 'unchanged' or 'equals'"
    )


def collect_security(
    scenarios: list[dict[str, Any]],
    base_url: str,
    timeout_seconds: float,
    allow_mutating: bool,
) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for scenario in scenarios:
        case_id = str(scenario.get("case_id", ""))
        if not case_id:
            raise EvaluationError("security scenario is missing case_id")
        mutating = scenario.get("mutating", False)
        if type(mutating) is not bool:
            raise EvaluationError(f"{case_id}: mutating must be a boolean")
        if mutating and not allow_mutating:
            raise EvaluationError(
                f"{case_id} is mutating; rerun with --allow-mutating only in an isolated test account"
            )
        request_config = _expand_env(scenario.get("request") or {})
        side_effect_config = _expand_env(scenario.get("side_effect_check") or {})
        probe_config = side_effect_config.get("request")
        if not isinstance(probe_config, dict):
            raise EvaluationError(
                f"{case_id}: side_effect_check.request is required"
            )
        try:
            before_status, before_payload = _security_request(
                base_url,
                probe_config,
                timeout_seconds,
                f"eval-security-{case_id}-before",
            )
        except Exception as exc:
            raise EvaluationError(
                f"{case_id}: side-effect preflight probe failed: {exc}"
            ) from exc
        accepted_probe_statuses = side_effect_config.get("http_status_in", [200])
        probe_path = str(side_effect_config.get("json_path", "$"))
        probe_path_present, _ = _json_path_lookup(before_payload, probe_path)
        if (
            before_status not in accepted_probe_statuses
            or not probe_path_present
        ):
            raise EvaluationError(
                f"{case_id}: side-effect preflight probe did not produce "
                "an accepted status and JSON path"
            )
        started = time.perf_counter()
        try:
            status, payload = _security_request(
                base_url,
                request_config,
                timeout_seconds,
                f"eval-security-{case_id}-attack",
            )
            error = None
        except Exception as exc:
            status, payload = 0, None
            error = f"{type(exc).__name__}: {exc}"
        try:
            after_status, after_payload = _security_request(
                base_url,
                probe_config,
                timeout_seconds,
                f"eval-security-{case_id}-after",
            )
            side_effect_safe = _side_effect_is_safe(
                before_status,
                before_payload,
                after_status,
                after_payload,
                side_effect_config,
            )
        except Exception as exc:
            after_status = 0
            side_effect_safe = False
            suffix = f"{type(exc).__name__}: {exc}"
            error = f"{error}; {suffix}" if error else suffix
        latency_ms = (time.perf_counter() - started) * 1000
        condition = scenario.get("blocked_when") or {"http_status_in": [400, 401, 403]}
        response_blocked = _is_blocked(status, payload, condition)
        output.append(
            {
                "case_id": case_id,
                "attack_type": scenario.get("attack_type"),
                "response_blocked": response_blocked,
                "side_effect_safe": side_effect_safe,
                "blocked": response_blocked and side_effect_safe,
                "http_status": status,
                "side_effect_before_http_status": before_status,
                "side_effect_after_http_status": after_status,
                "latency_ms": round(latency_ms, 3),
                "error": error,
            }
        )
    return output


def collect_async_tasks(
    cases: list[dict[str, Any]],
    base_url: str,
    token: str,
    timeout_seconds: float,
    poll_interval_seconds: float,
) -> list[dict[str, Any]]:
    """Poll already fault-injected tasks until a terminal state is observed."""
    output: list[dict[str, Any]] = []
    terminal_statuses = {"SUCCESS", "DEAD", "CANCELLED"}
    for case in cases:
        case_id = str(case.get("case_id", ""))
        task_id = case.get("task_id")
        if not case_id or task_id is None:
            raise EvaluationError("async collection cases require case_id and task_id")
        endpoint = urllib.parse.urljoin(
            base_url.rstrip("/") + "/", f"asyncTask/{task_id}"
        )
        started = time.perf_counter()
        last_data: dict[str, Any] = {}
        last_status: int | None = None
        error: str | None = None
        while time.perf_counter() - started < timeout_seconds:
            try:
                last_status, payload = _http_request(
                    endpoint,
                    "GET",
                    {
                        "Accept": "application/json",
                        "Authorization": token,
                        "X-Request-ID": f"eval-async-{case_id}",
                    },
                    None,
                    min(30, timeout_seconds),
                )
                data = payload.get("data") if isinstance(payload, dict) else None
                if isinstance(data, dict):
                    last_data = data
                    if str(data.get("status", "")).upper() in terminal_statuses:
                        break
                elif last_status and last_status >= 400:
                    error = f"HTTP {last_status}"
                    break
            except Exception as exc:
                error = f"{type(exc).__name__}: {exc}"
            time.sleep(max(0.1, poll_interval_seconds))

        elapsed_ms = (time.perf_counter() - started) * 1000
        final_status = str(last_data.get("status", "TIMEOUT")).upper()
        result = {
            "case_id": case_id,
            "fault": case.get("fault"),
            "task_id": task_id,
            "final_status": final_status,
            "execution_count": int(last_data.get("executionCount") or 0),
            "business_effect_count": case.get("business_effect_count"),
            "fault_injected_at": case.get("fault_injected_at"),
            "terminal_at": last_data.get("completedAt"),
            "collection_elapsed_ms": round(elapsed_ms, 3),
            "http_status": last_status,
            "error": error,
        }
        if not result["fault_injected_at"] or not result["terminal_at"]:
            # This is a lower bound when collection starts after injection.
            result["recovery_time_ms"] = round(elapsed_ms, 3)
            result["recovery_time_is_lower_bound"] = True
        result["collected_at"] = datetime.now(timezone.utc).isoformat()
        output.append(result)
    return output
