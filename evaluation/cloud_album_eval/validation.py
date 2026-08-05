from __future__ import annotations

from typing import Any

from .metrics import EvaluationError


AGENT_TOOL_NAMES = {
    "getAgentCapabilities",
    "searchFiles",
    "listAlbums",
    "listLocationAlbums",
    "listModelAlbums",
    "listTags",
    "listPeople",
    "previewAlbumAction",
    "executeAlbumAction",
    "previewTagAction",
    "executeTagAction",
    "getPendingActionStatus",
    "cancelPendingAction",
}

SECURITY_ATTACK_TYPES = {
    "unauthorized_access",
    "confirmation_bypass",
    "parameter_tampering",
}


def _require_object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvaluationError(f"{field} must be an object")
    return value


def _require_non_empty_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvaluationError(f"{field} must be a non-empty string")
    return value


def _require_bool(value: Any, field: str) -> bool:
    if type(value) is not bool:
        raise EvaluationError(f"{field} must be a boolean")
    return value


def _unique_case_ids(records: list[dict[str, Any]], dataset: str) -> None:
    seen: set[str] = set()
    for index, record in enumerate(records):
        case_id = _require_non_empty_string(
            record.get("case_id"), f"{dataset}[{index}].case_id"
        )
        if case_id in seen:
            raise EvaluationError(f"{dataset}: duplicate case_id {case_id}")
        seen.add(case_id)


def _validate_tool_calls(value: Any, field: str) -> None:
    if not isinstance(value, list):
        raise EvaluationError(f"{field} must be an array")
    for index, call in enumerate(value):
        item = _require_object(call, f"{field}[{index}]")
        name = _require_non_empty_string(item.get("name"), f"{field}[{index}].name")
        if name not in AGENT_TOOL_NAMES:
            raise EvaluationError(f"{field}[{index}].name is not an allowed tool: {name}")
        _require_object(item.get("arguments"), f"{field}[{index}].arguments")


def validate_agent_gold(records: list[dict[str, Any]]) -> None:
    if not records:
        raise EvaluationError("agent gold dataset must not be empty")
    _unique_case_ids(records, "agent_gold")
    for index, record in enumerate(records):
        _require_non_empty_string(record.get("prompt"), f"agent_gold[{index}].prompt")
        _require_non_empty_string(
            record.get("category"), f"agent_gold[{index}].category"
        )
        expected = _require_object(
            record.get("expected"), f"agent_gold[{index}].expected"
        )
        _validate_tool_calls(
            expected.get("tool_calls"), f"agent_gold[{index}].expected.tool_calls"
        )


def validate_agent_predictions(records: list[dict[str, Any]]) -> None:
    if not records:
        raise EvaluationError("agent prediction dataset must not be empty")
    _unique_case_ids(records, "agent_predictions")
    for index, record in enumerate(records):
        actual = _require_object(
            record.get("actual"), f"agent_predictions[{index}].actual"
        )
        _validate_tool_calls(
            actual.get("tool_calls"),
            f"agent_predictions[{index}].actual.tool_calls",
        )
        _require_bool(
            actual.get("completed"),
            f"agent_predictions[{index}].actual.completed",
        )
        verification = _require_object(
            actual.get("verification"),
            f"agent_predictions[{index}].actual.verification",
        )
        _require_non_empty_string(
            verification.get("source"),
            f"agent_predictions[{index}].actual.verification.source",
        )
        _require_bool(
            verification.get("passed"),
            f"agent_predictions[{index}].actual.verification.passed",
        )


def _validate_request(value: Any, field: str) -> None:
    request = _require_object(value, field)
    method = _require_non_empty_string(request.get("method"), f"{field}.method")
    if method.upper() not in {"GET", "POST", "PUT", "PATCH", "DELETE"}:
        raise EvaluationError(f"{field}.method is not supported: {method}")
    _require_non_empty_string(request.get("path"), f"{field}.path")
    if "headers" in request:
        _require_object(request["headers"], f"{field}.headers")


def validate_security_scenarios(records: list[dict[str, Any]]) -> None:
    if not records:
        raise EvaluationError("security scenario dataset must not be empty")
    _unique_case_ids(records, "security_scenarios")
    for index, record in enumerate(records):
        prefix = f"security_scenarios[{index}]"
        attack_type = _require_non_empty_string(
            record.get("attack_type"), f"{prefix}.attack_type"
        )
        if attack_type not in SECURITY_ATTACK_TYPES:
            raise EvaluationError(
                f"{prefix}.attack_type is not supported: {attack_type}"
            )
        if "mutating" in record:
            _require_bool(record["mutating"], f"{prefix}.mutating")
        _validate_request(record.get("request"), f"{prefix}.request")
        blocked_when = _require_object(
            record.get("blocked_when"), f"{prefix}.blocked_when"
        )
        if not blocked_when.get("http_status_in") and not blocked_when.get(
            "json_path"
        ):
            raise EvaluationError(
                f"{prefix}.blocked_when needs http_status_in or json_path"
            )
        side_effect_check = _require_object(
            record.get("side_effect_check"), f"{prefix}.side_effect_check"
        )
        _validate_request(
            side_effect_check.get("request"),
            f"{prefix}.side_effect_check.request",
        )
        comparison = str(side_effect_check.get("comparison", "unchanged"))
        if comparison not in {"unchanged", "equals"}:
            raise EvaluationError(
                f"{prefix}.side_effect_check.comparison is not supported"
            )
        _require_non_empty_string(
            side_effect_check.get("json_path"),
            f"{prefix}.side_effect_check.json_path",
        )


def validate_security_observations(records: list[dict[str, Any]]) -> None:
    if not records:
        raise EvaluationError("security observation dataset must not be empty")
    _unique_case_ids(records, "security_observations")
    for index, record in enumerate(records):
        prefix = f"security_observations[{index}]"
        attack_type = _require_non_empty_string(
            record.get("attack_type"), f"{prefix}.attack_type"
        )
        if attack_type not in SECURITY_ATTACK_TYPES:
            raise EvaluationError(
                f"{prefix}.attack_type is not supported: {attack_type}"
            )
        for field in ("response_blocked", "side_effect_safe", "blocked"):
            _require_bool(record.get(field), f"{prefix}.{field}")
        if record["blocked"] != (
            record["response_blocked"] and record["side_effect_safe"]
        ):
            raise EvaluationError(
                f"{prefix}.blocked must equal response_blocked && side_effect_safe"
            )
        if "skipped" in record:
            _require_bool(record["skipped"], f"{prefix}.skipped")
