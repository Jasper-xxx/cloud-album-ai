from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .metrics import EvaluationError


def read_records(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise EvaluationError(f"input file does not exist: {path}")
    if path.suffix.lower() == ".jsonl":
        records: list[dict[str, Any]] = []
        with path.open("r", encoding="utf-8-sig") as handle:
            for line_number, line in enumerate(handle, start=1):
                stripped = line.strip()
                if not stripped or stripped.startswith("#"):
                    continue
                try:
                    record = json.loads(stripped)
                except json.JSONDecodeError as exc:
                    raise EvaluationError(
                        f"{path}:{line_number}: invalid JSON: {exc.msg}"
                    ) from exc
                if not isinstance(record, dict):
                    raise EvaluationError(f"{path}:{line_number}: record must be an object")
                records.append(record)
        return records

    with path.open("r", encoding="utf-8-sig") as handle:
        payload = json.load(handle)
    if isinstance(payload, dict) and isinstance(payload.get("records"), list):
        payload = payload["records"]
    if not isinstance(payload, list) or not all(isinstance(item, dict) for item in payload):
        raise EvaluationError(f"{path}: JSON input must be an array of objects")
    return payload


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")


def load_manifest(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        manifest = json.load(handle)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("suites"), dict):
        raise EvaluationError("manifest must contain an object field named suites")
    if "sample_data" in manifest and type(manifest["sample_data"]) is not bool:
        raise EvaluationError("manifest.sample_data must be a boolean")
    for name, config in manifest["suites"].items():
        if not isinstance(config, dict):
            raise EvaluationError(f"manifest suite {name} must be an object")
        if "enabled" in config and type(config["enabled"]) is not bool:
            raise EvaluationError(f"manifest suite {name}.enabled must be a boolean")
    return manifest


def resolve_input(manifest_path: Path, value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = manifest_path.parent / path
    return path.resolve()


def merge_gold_predictions(
    gold: list[dict[str, Any]], predictions: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    prediction_by_id: dict[str, dict[str, Any]] = {}
    for prediction in predictions:
        case_id = str(prediction.get("case_id", ""))
        if not case_id:
            raise EvaluationError("prediction record is missing case_id")
        if case_id in prediction_by_id:
            raise EvaluationError(f"duplicate prediction case_id: {case_id}")
        prediction_by_id[case_id] = prediction

    merged: list[dict[str, Any]] = []
    for expected in gold:
        case_id = str(expected.get("case_id", ""))
        if not case_id:
            raise EvaluationError("gold record is missing case_id")
        actual = prediction_by_id.pop(case_id, None)
        if actual is None:
            raise EvaluationError(f"missing prediction for case_id: {case_id}")
        combined = dict(expected)
        for key, value in actual.items():
            if key != "case_id":
                combined[key] = value
        merged.append(combined)
    if prediction_by_id:
        extra = ", ".join(sorted(prediction_by_id)[:5])
        raise EvaluationError(f"predictions contain unknown case_id(s): {extra}")
    return merged


def load_suite_records(manifest_path: Path, suite_config: dict[str, Any]) -> list[dict[str, Any]]:
    if suite_config.get("input"):
        records = read_records(resolve_input(manifest_path, str(suite_config["input"])))
        schema = suite_config.get("schema")
        if schema == "agent":
            from .validation import validate_agent_gold, validate_agent_predictions

            validate_agent_gold(records)
            validate_agent_predictions(records)
        elif schema == "security_observation":
            from .validation import validate_security_observations

            validate_security_observations(records)
        return records
    if suite_config.get("gold") and suite_config.get("predictions"):
        gold = read_records(resolve_input(manifest_path, str(suite_config["gold"])))
        predictions = read_records(
            resolve_input(manifest_path, str(suite_config["predictions"]))
        )
        if suite_config.get("schema") == "agent":
            from .validation import validate_agent_gold, validate_agent_predictions

            validate_agent_gold(gold)
            validate_agent_predictions(predictions)
        return merge_gold_predictions(gold, predictions)
    raise EvaluationError("suite needs input, or both gold and predictions")
