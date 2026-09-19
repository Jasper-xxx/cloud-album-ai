from __future__ import annotations

import json
import math
from collections import Counter
from datetime import datetime
from typing import Any, Callable, Iterable


class EvaluationError(ValueError):
    """Raised when an evaluation input is incomplete or malformed."""


def _strict_bool(value: Any, field: str) -> bool:
    """Return a JSON boolean without accepting truthy strings or numbers."""
    if type(value) is not bool:
        raise EvaluationError(f"{field} must be a boolean")
    return value


def _rate(numerator: int | float, denominator: int | float) -> float | None:
    if denominator == 0:
        return None
    return numerator / denominator


def _f1(precision: float | None, recall: float | None) -> float | None:
    if precision is None or recall is None:
        return None
    if precision + recall == 0:
        return 0.0
    return 2 * precision * recall / (precision + recall)


def nearest_rank_percentile(values: Iterable[float], percentile: float) -> float | None:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        return None
    if percentile < 0 or percentile > 100:
        raise EvaluationError("percentile must be in [0, 100]")
    rank = max(1, math.ceil((percentile / 100) * len(ordered)))
    return ordered[min(rank - 1, len(ordered) - 1)]


def _unique(values: Iterable[Any]) -> list[Any]:
    result: list[Any] = []
    seen: set[str] = set()
    for value in values:
        key = _stable_value(value)
        if key not in seen:
            seen.add(key)
            result.append(value)
    return result


def _stable_value(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def evaluate_image_search(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        raise EvaluationError("image_search requires at least one record")

    recalls_at_5: list[float] = []
    recalls_at_10: list[float] = []
    hits_at_5 = 0
    hits_at_10 = 0
    latencies: list[float] = []
    errors = 0
    failed_case_ids: list[str] = []

    for index, record in enumerate(records):
        case_id = str(record.get("case_id", f"row-{index + 1}"))
        relevant = _unique(record.get("relevant_ids") or [])
        if not relevant:
            raise EvaluationError(f"image_search {case_id}: relevant_ids cannot be empty")
        returned = _unique(record.get("returned_ids") or [])
        relevant_keys = {_stable_value(value) for value in relevant}
        top5_keys = {_stable_value(value) for value in returned[:5]}
        top10_keys = {_stable_value(value) for value in returned[:10]}
        recall5 = len(relevant_keys & top5_keys) / len(relevant_keys)
        recall10 = len(relevant_keys & top10_keys) / len(relevant_keys)
        recalls_at_5.append(recall5)
        recalls_at_10.append(recall10)
        hits_at_5 += int(recall5 > 0)
        hits_at_10 += int(recall10 > 0)
        if recall10 < 1.0:
            failed_case_ids.append(case_id)
        if record.get("error"):
            errors += 1
        if record.get("latency_ms") is not None:
            latencies.append(float(record["latency_ms"]))

    query_count = len(records)
    return {
        "query_count": query_count,
        "recall_at_5": sum(recalls_at_5) / query_count,
        "recall_at_10": sum(recalls_at_10) / query_count,
        "hit_rate_at_5": hits_at_5 / query_count,
        "hit_rate_at_10": hits_at_10 / query_count,
        "latency_p95_ms": nearest_rank_percentile(latencies, 95),
        "latency_sample_count": len(latencies),
        "query_error_rate": errors / query_count,
        "failed_case_ids": failed_case_ids,
    }


def _comb2(value: int) -> int:
    return value * (value - 1) // 2


def evaluate_face_clustering(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        raise EvaluationError("face_clustering requires at least one face")

    truth_counts: Counter[str] = Counter()
    predicted_counts: Counter[str] = Counter()
    intersections: Counter[tuple[str, str]] = Counter()
    seen_face_ids: set[str] = set()
    corrected_count = 0
    correction_annotations = 0

    for index, record in enumerate(records):
        face_id = str(record.get("face_id", f"row-{index + 1}"))
        if face_id in seen_face_ids:
            raise EvaluationError(f"face_clustering: duplicate face_id {face_id}")
        seen_face_ids.add(face_id)
        if record.get("truth_person_id") is None:
            raise EvaluationError(f"face_clustering {face_id}: truth_person_id is required")
        truth_key = _stable_value(record["truth_person_id"])
        predicted_value = record.get("predicted_cluster_id")
        # Every unclustered/null prediction is a singleton, not one shared cluster.
        predicted_key = (
            f"__unclustered__:{face_id}"
            if predicted_value is None
            else _stable_value(predicted_value)
        )
        truth_counts[truth_key] += 1
        predicted_counts[predicted_key] += 1
        intersections[(truth_key, predicted_key)] += 1

        if "corrected" in record:
            correction_annotations += 1
            corrected_count += int(
                _strict_bool(record["corrected"], f"face_clustering {face_id}.corrected")
            )
        elif "corrected_cluster_id" in record:
            correction_annotations += 1
            corrected_count += int(
                _stable_value(record.get("corrected_cluster_id"))
                != _stable_value(predicted_value)
            )

    true_pairs = sum(_comb2(count) for count in truth_counts.values())
    predicted_pairs = sum(_comb2(count) for count in predicted_counts.values())
    true_positive_pairs = sum(_comb2(count) for count in intersections.values())
    false_positive_pairs = predicted_pairs - true_positive_pairs
    false_negative_pairs = true_pairs - true_positive_pairs
    precision = _rate(true_positive_pairs, predicted_pairs)
    recall = _rate(true_positive_pairs, true_pairs)

    return {
        "face_count": len(records),
        "truth_identity_count": len(truth_counts),
        "predicted_cluster_count": len(predicted_counts),
        "pairwise_precision": precision,
        "pairwise_recall": recall,
        "pairwise_f1": _f1(precision, recall),
        "wrong_merge_rate": _rate(false_positive_pairs, predicted_pairs),
        "false_split_rate": _rate(false_negative_pairs, true_pairs),
        "manual_correction_rate": (
            corrected_count / len(records) if correction_annotations else None
        ),
        "correction_annotation_coverage": correction_annotations / len(records),
        "pair_counts": {
            "true_positive": true_positive_pairs,
            "false_positive": false_positive_pairs,
            "false_negative": false_negative_pairs,
            "predicted_positive": predicted_pairs,
            "actual_positive": true_pairs,
        },
    }


def _tool_name(call: dict[str, Any]) -> str:
    return str(call.get("name", call.get("tool", ""))).strip()


def _tool_arguments(call: dict[str, Any]) -> dict[str, Any]:
    arguments = call.get("arguments", call.get("args", {}))
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except json.JSONDecodeError:
            return {"__raw__": arguments.strip()}
    return arguments if isinstance(arguments, dict) else {"__value__": arguments}


def _normalize_argument(value: Any) -> Any:
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        return [_normalize_argument(item) for item in value]
    if isinstance(value, dict):
        return {key: _normalize_argument(item) for key, item in sorted(value.items())}
    return value


def _flatten(value: Any, prefix: str = "") -> dict[str, str]:
    if isinstance(value, dict):
        if not value:
            return {prefix or "$": "{}"}
        result: dict[str, str] = {}
        for key, item in value.items():
            child = f"{prefix}.{key}" if prefix else str(key)
            result.update(_flatten(item, child))
        return result
    return {prefix or "$": _stable_value(_normalize_argument(value))}


def evaluate_agent(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        raise EvaluationError("agent requires at least one record")

    tool_exact = 0
    parameter_exact = 0
    completed = 0
    matched_parameter_fields = 0
    total_parameter_fields = 0
    tool_failures: list[str] = []
    parameter_failures: list[str] = []
    incomplete_cases: list[str] = []
    unverifiable_cases: list[str] = []
    latencies: list[float] = []
    token_counts: list[int] = []

    for index, record in enumerate(records):
        case_id = str(record.get("case_id", f"row-{index + 1}"))
        expected = record.get("expected") or {}
        actual = record.get("actual") or {}
        expected_calls = expected.get("tool_calls") or []
        actual_calls = actual.get("tool_calls") or []
        expected_names = [_tool_name(call) for call in expected_calls]
        actual_names = [_tool_name(call) for call in actual_calls]
        names_match = expected_names == actual_names
        tool_exact += int(names_match)
        if not names_match:
            tool_failures.append(case_id)

        case_parameters_match = len(expected_calls) == len(actual_calls)
        max_calls = max(len(expected_calls), len(actual_calls))
        for call_index in range(max_calls):
            expected_args = (
                _flatten(_tool_arguments(expected_calls[call_index]))
                if call_index < len(expected_calls)
                else {}
            )
            actual_args = (
                _flatten(_tool_arguments(actual_calls[call_index]))
                if call_index < len(actual_calls)
                else {}
            )
            field_names = set(expected_args) | set(actual_args)
            total_parameter_fields += len(field_names)
            for field_name in field_names:
                field_match = expected_args.get(field_name) == actual_args.get(field_name)
                matched_parameter_fields += int(field_match)
                case_parameters_match = case_parameters_match and field_match
        parameter_exact += int(case_parameters_match)
        if not case_parameters_match:
            parameter_failures.append(case_id)

        if "completed" not in actual:
            raise EvaluationError(f"agent {case_id}: actual.completed is required")
        reported_completed = _strict_bool(
            actual["completed"], f"agent {case_id}.actual.completed"
        )
        verification = actual.get("verification")
        if not isinstance(verification, dict):
            raise EvaluationError(
                f"agent {case_id}: actual.verification must be an object"
            )
        source = str(verification.get("source", "")).strip()
        if not source:
            raise EvaluationError(
                f"agent {case_id}: actual.verification.source is required"
            )
        if "passed" not in verification:
            raise EvaluationError(
                f"agent {case_id}: actual.verification.passed is required"
            )
        verification_passed = _strict_bool(
            verification["passed"],
            f"agent {case_id}.actual.verification.passed",
        )
        is_completed = reported_completed and verification_passed
        completed += int(is_completed)
        if not is_completed:
            incomplete_cases.append(case_id)
        if not verification_passed:
            unverifiable_cases.append(case_id)
        if actual.get("latency_ms") is not None:
            latencies.append(float(actual["latency_ms"]))
        if actual.get("total_tokens") is not None:
            token_counts.append(int(actual["total_tokens"]))

    case_count = len(records)
    return {
        "case_count": case_count,
        "tool_selection_accuracy": tool_exact / case_count,
        "parameter_extraction_accuracy": _rate(
            matched_parameter_fields, total_parameter_fields
        ),
        "parameter_case_exact_match_rate": parameter_exact / case_count,
        "task_completion_rate": completed / case_count,
        "verification_coverage": (case_count - len(unverifiable_cases)) / case_count,
        "agent_latency_p95_ms": nearest_rank_percentile(latencies, 95),
        "average_tokens_per_case": (
            sum(token_counts) / len(token_counts) if token_counts else None
        ),
        "parameter_field_count": total_parameter_fields,
        "tool_failure_case_ids": tool_failures,
        "parameter_failure_case_ids": parameter_failures,
        "incomplete_case_ids": incomplete_cases,
        "unverifiable_case_ids": unverifiable_cases,
    }


def evaluate_security(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        raise EvaluationError("security requires at least one record")

    totals: Counter[str] = Counter()
    blocked: Counter[str] = Counter()
    false_negatives: list[str] = []
    unsafe_side_effect_cases: list[str] = []
    skipped = 0
    for index, record in enumerate(records):
        if "skipped" in record and _strict_bool(
            record["skipped"], f"security row-{index + 1}.skipped"
        ):
            skipped += 1
            continue
        case_id = str(record.get("case_id", f"row-{index + 1}"))
        attack_type = str(record.get("attack_type", "")).strip()
        if not attack_type:
            raise EvaluationError(f"security {case_id}: attack_type is required")
        if "response_blocked" not in record:
            raise EvaluationError(f"security {case_id}: response_blocked is required")
        if "side_effect_safe" not in record:
            raise EvaluationError(f"security {case_id}: side_effect_safe is required")
        totals[attack_type] += 1
        response_blocked = _strict_bool(
            record["response_blocked"],
            f"security {case_id}.response_blocked",
        )
        side_effect_safe = _strict_bool(
            record["side_effect_safe"],
            f"security {case_id}.side_effect_safe",
        )
        was_blocked = response_blocked and side_effect_safe
        if "blocked" in record:
            declared_blocked = _strict_bool(
                record["blocked"], f"security {case_id}.blocked"
            )
            if declared_blocked != was_blocked:
                raise EvaluationError(
                    f"security {case_id}: blocked must equal "
                    "response_blocked && side_effect_safe"
                )
        blocked[attack_type] += int(was_blocked)
        if not was_blocked:
            false_negatives.append(case_id)
        if not side_effect_safe:
            unsafe_side_effect_cases.append(case_id)

    evaluated_count = sum(totals.values())
    if evaluated_count == 0:
        raise EvaluationError("security has no evaluated records after skipped cases")
    return {
        "attack_case_count": evaluated_count,
        "skipped_case_count": skipped,
        "interception_rate": sum(blocked.values()) / evaluated_count,
        "side_effect_verification_rate": (
            evaluated_count - len(unsafe_side_effect_cases)
        )
        / evaluated_count,
        "interception_rate_by_attack_type": {
            attack_type: blocked[attack_type] / count
            for attack_type, count in sorted(totals.items())
        },
        "case_count_by_attack_type": dict(sorted(totals.items())),
        "false_negative_case_ids": false_negatives,
        "unsafe_side_effect_case_ids": unsafe_side_effect_cases,
    }


def _parse_time(value: str) -> datetime:
    normalized = value.strip().replace("Z", "+00:00")
    return datetime.fromisoformat(normalized)


def _recovery_time_ms(record: dict[str, Any]) -> float | None:
    if record.get("recovery_time_ms") is not None:
        return float(record["recovery_time_ms"])
    injected_at = record.get("fault_injected_at")
    terminal_at = record.get("terminal_at", record.get("completed_at"))
    if injected_at and terminal_at:
        terminal_time = _parse_time(str(terminal_at))
        injected_time = _parse_time(str(injected_at))
        if terminal_time.tzinfo is None and injected_time.tzinfo is not None:
            terminal_time = terminal_time.replace(
                tzinfo=datetime.now().astimezone().tzinfo
            )
        if injected_time.tzinfo is None and terminal_time.tzinfo is not None:
            injected_time = injected_time.replace(
                tzinfo=datetime.now().astimezone().tzinfo
            )
        return (terminal_time - injected_time).total_seconds() * 1000
    return None


def evaluate_async_tasks(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        raise EvaluationError("async_tasks requires at least one fault-injection record")

    success_count = 0
    duplicate_executions = 0
    cases_with_duplicate_execution = 0
    duplicate_effects = 0
    effect_observation_count = 0
    recovery_times: list[float] = []
    failed_cases: list[str] = []

    for index, record in enumerate(records):
        case_id = str(record.get("case_id", f"row-{index + 1}"))
        final_status = str(record.get("final_status", "")).upper()
        succeeded = final_status == "SUCCESS"
        success_count += int(succeeded)
        if not succeeded:
            failed_cases.append(case_id)

        execution_count = int(
            record.get("execution_count", record.get("handler_attempt_count", 0))
        )
        if execution_count < 0:
            raise EvaluationError(f"async_tasks {case_id}: execution_count cannot be negative")
        extra_executions = max(0, execution_count - 1)
        duplicate_executions += extra_executions
        cases_with_duplicate_execution += int(extra_executions > 0)

        if record.get("business_effect_count") is not None:
            effect_observation_count += 1
            duplicate_effects += max(0, int(record["business_effect_count"]) - 1)

        recovery_ms = _recovery_time_ms(record)
        if recovery_ms is not None:
            if recovery_ms < 0:
                raise EvaluationError(f"async_tasks {case_id}: recovery time cannot be negative")
            recovery_times.append(recovery_ms)

    case_count = len(records)
    return {
        "fault_case_count": case_count,
        "eventual_success_rate": success_count / case_count,
        "duplicate_execution_count": duplicate_executions,
        "cases_with_duplicate_execution": cases_with_duplicate_execution,
        "duplicate_business_effect_count": (
            duplicate_effects if effect_observation_count else None
        ),
        "business_effect_observation_coverage": effect_observation_count / case_count,
        "recovery_time_avg_ms": (
            sum(recovery_times) / len(recovery_times) if recovery_times else None
        ),
        "recovery_time_p95_ms": nearest_rank_percentile(recovery_times, 95),
        "recovery_time_sample_count": len(recovery_times),
        "failed_case_ids": failed_cases,
    }


EVALUATORS: dict[str, Callable[[list[dict[str, Any]]], dict[str, Any]]] = {
    "image_search": evaluate_image_search,
    "face_clustering": evaluate_face_clustering,
    "agent": evaluate_agent,
    "security": evaluate_security,
    "async_tasks": evaluate_async_tasks,
}


def evaluate_suite(name: str, records: list[dict[str, Any]]) -> dict[str, Any]:
    try:
        evaluator = EVALUATORS[name]
    except KeyError as exc:
        raise EvaluationError(f"unsupported suite: {name}") from exc
    return evaluator(records)
