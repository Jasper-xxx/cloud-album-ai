from __future__ import annotations

import json
import mimetypes
import os
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .metrics import EvaluationError


DIFY_TOOL_NODE_NAMES = {
    "tool_capabilities": "getAgentCapabilities",
    "tool_search_tag": "searchFiles",
    "tool_list_albums": "listAlbums",
    "tool_list_location_albums": "listLocationAlbums",
    "tool_list_model_albums": "listModelAlbums",
    "tool_list_tags": "listTags",
    "tool_list_people": "listPeople",
    "tool_advanced_search": "advancedSearchFiles",
    "tool_preview_album_action": "previewAlbumAction",
    "tool_preview_tag_action": "previewTagAction",
    "tool_execute_album_action": "executeAlbumAction",
    "tool_execute_tag_action": "executeTagAction",
    "tool_get_pending_action_status": "getPendingActionStatus",
    "tool_cancel_pending_action": "cancelPendingAction",
}


def _redact_presigned_urls(value: str) -> str:
    """Keep object paths useful for debugging without persisting signed query data."""
    return re.sub(
        r"\?X-Amz-Algorithm=[^\s\"'<>\)\]\}]+",
        "?[REDACTED_PRESIGNED_QUERY]",
        value,
        flags=re.IGNORECASE,
    )


def _http_request(
    url: str,
    method: str,
    headers: dict[str, str],
    body: bytes | None,
    timeout_seconds: float,
) -> tuple[int, Any]:
    request = urllib.request.Request(url=url, data=body, headers=headers, method=method)
    try:
        with _direct_urlopen(request, timeout_seconds) as response:
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


def _direct_urlopen(request: urllib.request.Request, timeout_seconds: float):
    """Open local evaluation URLs without inheriting a desktop HTTP proxy."""
    return urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
        request, timeout=timeout_seconds
    )


def _dify_passport(origin: str, app_code: str, timeout_seconds: float) -> str:
    request = urllib.request.Request(
        urllib.parse.urljoin(origin.rstrip("/") + "/", "api/passport"),
        headers={"Accept": "application/json", "X-App-Code": app_code},
        method="GET",
    )
    try:
        with _direct_urlopen(request, timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, urllib.error.HTTPError, json.JSONDecodeError) as exc:
        raise EvaluationError(f"failed to obtain Dify WebApp passport: {exc}") from exc
    token = payload.get("access_token") if isinstance(payload, dict) else None
    if not token:
        raise EvaluationError("Dify WebApp passport response has no access_token")
    return str(token)


def _dify_chat(
    origin: str,
    app_code: str,
    passport: str | None,
    prompt: str,
    timeout_seconds: float,
    api_key: str | None = None,
    user: str = "cloud-album-eval",
) -> dict[str, Any]:
    service_api = bool(api_key)
    endpoint = urllib.parse.urljoin(
        origin.rstrip("/") + "/",
        "v1/chat-messages" if service_api else "api/chat-messages",
    )
    payload = {
        "response_mode": "streaming",
        "conversation_id": "",
        "files": [],
        "query": prompt,
        "inputs": {},
    }
    if service_api:
        payload["user"] = user
    else:
        payload["parent_message_id"] = None
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Accept": "text/event-stream", "Content-Type": "application/json"}
    if service_api:
        headers["Authorization"] = f"Bearer {api_key}"
    else:
        headers["X-App-Code"] = app_code
        headers["X-App-Passport"] = str(passport or "")
    request = urllib.request.Request(
        endpoint,
        data=body,
        headers=headers,
        method="POST",
    )
    events: list[dict[str, Any]] = []
    started = time.perf_counter()
    try:
        with _direct_urlopen(request, timeout_seconds) as response:
            for raw_line in response:
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue
                try:
                    event = json.loads(line.removeprefix("data:").strip())
                except json.JSONDecodeError:
                    continue
                if isinstance(event, dict):
                    events.append(event)
    except (OSError, urllib.error.HTTPError) as exc:
        raise EvaluationError(f"Dify chat request failed: {exc}") from exc
    latency_ms = (time.perf_counter() - started) * 1000
    workflow_event = next(
        (event for event in reversed(events) if event.get("event") == "workflow_finished"),
        None,
    )
    workflow_data = workflow_event.get("data") if isinstance(workflow_event, dict) else {}
    if not isinstance(workflow_data, dict):
        workflow_data = {}
    run_id = (
        workflow_event.get("workflow_run_id")
        if isinstance(workflow_event, dict)
        else None
    )
    if not run_id:
        started_event = next(
            (event for event in events if event.get("event") == "workflow_started"),
            {},
        )
        run_id = started_event.get("workflow_run_id")
    tool_node_ids = [
        str(event.get("data", {}).get("node_id"))
        for event in events
        if event.get("event") == "node_started"
        and isinstance(event.get("data"), dict)
        and event["data"].get("node_type") == "tool"
    ]
    return {
        "workflow_run_id": str(run_id or ""),
        "conversation_id": str(
            next(
                (event.get("conversation_id") for event in events if event.get("conversation_id")),
                "",
            )
        ),
        "status": str(workflow_data.get("status", "missing")),
        "answer": _redact_presigned_urls(
            str((workflow_data.get("outputs") or {}).get("answer", ""))
        ),
        "total_tokens": int(workflow_data.get("total_tokens") or 0),
        "latency_ms": round(latency_ms, 3),
        "tool_node_ids": tool_node_ids,
        "error": workflow_data.get("error"),
    }


def _load_dify_traces(
    workflow_run_ids: list[str],
    container: str,
    database: str,
    database_user: str,
) -> dict[str, list[dict[str, Any]]]:
    if not workflow_run_ids:
        return {}
    quoted_ids = ",".join(f"'{run_id}'" for run_id in workflow_run_ids)
    sql = (
        "select json_build_object("
        "'workflow_run_id',workflow_run_id,'index',index,'node_id',node_id,"
        "'node_type',node_type,'status',status,'inputs',inputs,'outputs',outputs,"
        "'error',error)::text from workflow_node_executions "
        f"where workflow_run_id in ({quoted_ids}) order by workflow_run_id,index;"
    )
    command = [
        "docker", "exec", container, "psql", "-U", database_user,
        "-d", database, "-Atc", sql,
    ]
    try:
        completed = subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=60,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        detail = getattr(exc, "stderr", None) or str(exc)
        raise EvaluationError(f"failed to read Dify node traces: {detail}") from exc
    traces: dict[str, list[dict[str, Any]]] = {}
    for line in completed.stdout.splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        run_id = str(row["workflow_run_id"])
        for field in ("inputs", "outputs"):
            value = row.get(field)
            if isinstance(value, str) and value:
                try:
                    row[field] = json.loads(value)
                except json.JSONDecodeError:
                    pass
        traces.setdefault(run_id, []).append(row)
    return traces


def _planner_values(trace: list[dict[str, Any]], node_id: str) -> dict[str, Any]:
    row = next((item for item in trace if item.get("node_id") == node_id), None)
    if row:
        outputs = row.get("outputs")
        if isinstance(outputs, dict):
            return outputs
    return {}


def _tool_output_passed(row: dict[str, Any] | None) -> bool:
    if not row or row.get("status") != "succeeded":
        return False
    outputs = row.get("outputs")
    if not isinstance(outputs, dict):
        return False
    values = outputs.get("json")
    if isinstance(values, list) and values and isinstance(values[0], dict):
        return int(values[0].get("code") or 0) == 200
    text_value = outputs.get("text")
    if isinstance(text_value, str):
        try:
            payload = json.loads(text_value)
            return isinstance(payload, dict) and int(payload.get("code") or 0) == 200
        except (json.JSONDecodeError, TypeError, ValueError):
            return False
    return False


def collect_dify_agent_runs(
    gold_records: list[dict[str, Any]],
    webapp_url: str,
    app_code: str,
    timeout_seconds: float,
    delay_ms: int,
    api_key: str | None = None,
    service_api_base: str | None = None,
    user: str = "cloud-album-eval",
) -> list[dict[str, Any]]:
    parsed_url = urllib.parse.urlparse(webapp_url)
    if not parsed_url.scheme or not parsed_url.netloc:
        raise EvaluationError("webapp_url must be an absolute URL")
    origin = f"{parsed_url.scheme}://{parsed_url.netloc}"
    passport = None if api_key else _dify_passport(origin, app_code, timeout_seconds)
    request_origin = service_api_base.rstrip("/") if service_api_base else origin
    runs: list[dict[str, Any]] = []
    for index, gold in enumerate(gold_records):
        case_id = str(gold.get("case_id", ""))
        prompt = str(gold.get("prompt", ""))
        if not case_id or not prompt:
            raise EvaluationError("Agent gold records require case_id and prompt")
        try:
            run = _dify_chat(
                request_origin,
                app_code,
                passport,
                prompt,
                timeout_seconds,
                api_key=api_key,
                user=user,
            )
        except EvaluationError as exc:
            run = {
                "workflow_run_id": "",
                "status": "failed",
                "answer": "",
                "total_tokens": 0,
                "latency_ms": timeout_seconds * 1000,
                "tool_node_ids": [],
                "error": str(exc),
            }
        runs.append({"case_id": case_id, **run})
        print(
            f"agent {index + 1}/{len(gold_records)} {case_id}: "
            f"{run['status']} {run['latency_ms']:.0f} ms",
            flush=True,
        )
        if delay_ms > 0 and index < len(gold_records) - 1:
            time.sleep(delay_ms / 1000)

    return runs


def build_dify_agent_predictions(
    gold_records: list[dict[str, Any]],
    run_records: list[dict[str, Any]],
    trace_container: str,
    trace_database: str,
    trace_database_user: str,
) -> list[dict[str, Any]]:
    run_by_case = {str(run.get("case_id", "")): run for run in run_records}
    if len(run_by_case) != len(run_records) or "" in run_by_case:
        raise EvaluationError("Agent run records require unique non-empty case_id values")
    missing = [str(gold["case_id"]) for gold in gold_records if str(gold["case_id"]) not in run_by_case]
    if missing:
        raise EvaluationError(f"missing Agent run record(s): {', '.join(missing[:5])}")
    traces = _load_dify_traces(
        [run["workflow_run_id"] for run in run_records if run.get("workflow_run_id")],
        trace_container,
        trace_database,
        trace_database_user,
    )
    predictions: list[dict[str, Any]] = []
    for gold in gold_records:
        run = run_by_case[str(gold["case_id"])]
        trace = traces.get(run.get("workflow_run_id", ""), [])
        expected_calls = (gold.get("expected") or {}).get("tool_calls") or []
        actual_calls: list[dict[str, Any]] = []
        tool_rows = [row for row in trace if row.get("node_type") == "tool"]
        if not tool_rows:
            tool_rows = [
                {"node_id": node_id, "status": "unknown", "outputs": None}
                for node_id in run.get("tool_node_ids", [])
            ]
        from .business import verify_business
        for row in tool_rows:
            name = row.get("tool_name") or DIFY_TOOL_NODE_NAMES.get(str(row.get("node_id"))) or str(row.get("node_id", "unknown"))
            # Preserve observed tool inputs. Never reconstruct arguments from the gold or planner.
            inputs = row.get("inputs")
            arguments = inputs.get("tool_parameters", inputs) if isinstance(inputs, dict) else {}
            actual_calls.append({"name": name, "arguments": arguments if isinstance(arguments, dict) else {}})
        route_passed = [c["name"] for c in actual_calls] == [c["name"] for c in expected_calls]
        arguments_passed = route_passed and all(
            all(key in actual["arguments"] and actual["arguments"][key] == value
                for key, value in expected.get("arguments", {}).items())
            for actual, expected in zip(actual_calls, expected_calls)
        )
        tool_success = bool(tool_rows) and all(_tool_output_passed(row) for row in tool_rows)
        workflow_success = run.get("status") == "succeeded"
        business = verify_business(gold.get("expected") or {}, run.get("business_observation"), str(run.get("answer", "")))
        verification_passed = bool(workflow_success and route_passed and arguments_passed
                                   and (tool_success or not expected_calls)
                                   and business["passed"] and business["reply_passed"])
        predictions.append(
            {
                "case_id": str(gold["case_id"]),
                "actual": {
                    "tool_calls": actual_calls,
                    "completed": verification_passed,
                    "verification": {
                        "source": "independent_business_observation",
                        "passed": verification_passed,
                        "evidence_id": business["evidence_id"],
                    },
                    "latency_ms": run.get("latency_ms"),
                    "total_tokens": run.get("total_tokens", 0),
                },
            }
        )
    return predictions


def collect_dify_agent(
    gold_records: list[dict[str, Any]],
    webapp_url: str,
    app_code: str,
    timeout_seconds: float,
    delay_ms: int,
    trace_container: str,
    trace_database: str,
    trace_database_user: str,
    api_key: str | None = None,
    service_api_base: str | None = None,
    user: str = "cloud-album-eval",
) -> list[dict[str, Any]]:
    runs = collect_dify_agent_runs(
        gold_records,
        webapp_url,
        app_code,
        timeout_seconds,
        delay_ms,
        api_key=api_key,
        service_api_base=service_api_base,
        user=user,
    )
    return build_dify_agent_predictions(
        gold_records,
        runs,
        trace_container,
        trace_database,
        trace_database_user,
    )


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
        result_materialized = final_status == "SUCCESS" and last_data.get("result") is not None
        business_effect_count = case.get("business_effect_count")
        if business_effect_count is None and case.get("business_effect_probe") == "task_result":
            business_effect_count = int(result_materialized)
        result = {
            "case_id": case_id,
            "fault": case.get("fault"),
            "task_id": task_id,
            "final_status": final_status,
            "execution_count": int(last_data.get("executionCount") or 0),
            "business_effect_count": business_effect_count,
            "result_materialized": result_materialized,
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


def _list_user_async_tasks(
    base_url: str,
    token: str,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    endpoint = urllib.parse.urljoin(
        base_url.rstrip("/") + "/", "asyncTask/list?current=1&size=100"
    )
    status, payload = _http_request(
        endpoint,
        "GET",
        {"Accept": "application/json", "Authorization": token},
        None,
        timeout_seconds,
    )
    data = payload.get("data") if isinstance(payload, dict) else None
    records = data.get("records") if isinstance(data, dict) else None
    if status != 200 or not isinstance(records, list):
        raise EvaluationError(f"failed to list async tasks: HTTP {status}")
    return [record for record in records if isinstance(record, dict)]


def prepare_ai_outage_tasks(
    file_ids: list[str],
    batches: int,
    base_url: str,
    ai_service_url: str,
    token: str,
    timeout_seconds: float,
    poll_interval_seconds: float,
    case_id_start: int = 1,
) -> list[dict[str, Any]]:
    """Submit preview-only IMAGE_TAG tasks while ai-service is intentionally offline."""
    if not file_ids or batches < 1:
        raise EvaluationError("AI outage preparation needs file_ids and batches >= 1")

    health_endpoint = urllib.parse.urljoin(
        ai_service_url.rstrip("/") + "/", "health"
    )
    try:
        health_status, _ = _http_request(
            health_endpoint, "GET", {"Accept": "application/json"}, None, 2
        )
    except OSError:
        health_status = 0
    if health_status:
        raise EvaluationError(
            f"ai-service is still reachable (HTTP {health_status}); refusing a false outage test"
        )

    before_ids = {
        int(record["id"])
        for record in _list_user_async_tasks(base_url, token, timeout_seconds)
        if record.get("id") is not None
    }
    # Spring serializes LocalDateTime without an offset. Record injection in the
    # host's local zone so recovery durations compare like-for-like.
    injected_at = datetime.now().astimezone().isoformat()
    agent_task_ids: list[str] = []
    headers = {
        "Accept": "application/json",
        "Authorization": token,
        "Content-Type": "application/json",
    }
    for batch_index in range(batches):
        preview_status, preview_payload = _http_request(
            urllib.parse.urljoin(
                base_url.rstrip("/") + "/", "agent/previewImageTagTask"
            ),
            "POST",
            headers | {"X-Request-ID": f"eval-ai-outage-preview-{batch_index + 1}"},
            json.dumps(
                {"fileIds": file_ids, "minConfidence": 0.5}, ensure_ascii=False
            ).encode("utf-8"),
            timeout_seconds,
        )
        preview = preview_payload.get("data") if isinstance(preview_payload, dict) else None
        if preview_status != 200 or not isinstance(preview, dict):
            raise EvaluationError(
                f"AI outage preview batch {batch_index + 1} failed: HTTP {preview_status}"
            )
        execute_body = {
            "pendingActionId": preview.get("pendingActionId"),
            "confirmationToken": preview.get("confirmationToken"),
            "idempotencyKey": preview.get("idempotencyKey"),
            "confirmed": True,
        }
        submit_status, submit_payload = _http_request(
            urllib.parse.urljoin(
                base_url.rstrip("/") + "/", "agent/submitImageTagTask"
            ),
            "POST",
            headers | {"X-Request-ID": f"eval-ai-outage-submit-{batch_index + 1}"},
            json.dumps(execute_body, ensure_ascii=False).encode("utf-8"),
            timeout_seconds,
        )
        submitted = submit_payload.get("data") if isinstance(submit_payload, dict) else None
        if submit_status != 200 or not isinstance(submitted, dict):
            raise EvaluationError(
                f"AI outage submit batch {batch_index + 1} failed: HTTP {submit_status}"
            )
        agent_task_ids.append(str(submitted.get("agentTaskId", "")))

    expected_count = len(file_ids) * batches
    started = time.perf_counter()
    new_tasks: list[dict[str, Any]] = []
    while time.perf_counter() - started < timeout_seconds:
        tasks = _list_user_async_tasks(base_url, token, min(30, timeout_seconds))
        new_tasks = [
            record
            for record in tasks
            if record.get("id") is not None
            and int(record["id"]) not in before_ids
            and str(record.get("taskType", "")).upper() == "IMAGE_TAG"
        ]
        if len(new_tasks) >= expected_count and all(
            str(record.get("status", "")).upper() in {"FAILED", "DEAD"}
            for record in new_tasks[:expected_count]
        ):
            break
        time.sleep(max(0.1, poll_interval_seconds))
    new_tasks = sorted(new_tasks, key=lambda record: int(record["id"]))[:expected_count]
    if len(new_tasks) != expected_count:
        raise EvaluationError(
            f"expected {expected_count} new IMAGE_TAG tasks, observed {len(new_tasks)}"
        )
    if any(
        str(record.get("status", "")).upper() not in {"FAILED", "DEAD"}
        for record in new_tasks
    ):
        statuses = ", ".join(str(record.get("status")) for record in new_tasks)
        raise EvaluationError(f"outage was not observed as task failure: {statuses}")

    return [
        {
            "case_id": f"ai-outage-{index + case_id_start - 1:03d}",
            "fault": "ai-service-unavailable",
            "task_id": int(record["id"]),
            "file_id": record.get("fileId"),
            "agent_task_ids": agent_task_ids,
            "fault_injected_at": injected_at,
            "initial_status": str(record.get("status", "")).upper(),
            "initial_execution_count": int(record.get("executionCount") or 0),
            "initial_retry_count": int(record.get("retryCount") or 0),
            "business_effect_probe": "task_result",
            "auto_add_tag": False,
        }
        for index, record in enumerate(new_tasks, start=1)
    ]
