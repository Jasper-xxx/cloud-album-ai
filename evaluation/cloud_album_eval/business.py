"""Business assertions evaluated against independent, post-operation API observations."""
from typing import Any


def verify_business(expected: dict, observation: Any, answer: str) -> dict:
    assertions = expected.get("business_assertions") or []
    evidence = observation if isinstance(observation, dict) else {}
    evidence_id = evidence.get("evidence_id", "")
    snapshot = evidence.get("snapshot")
    outcomes = []
    for assertion in assertions:
        value = snapshot
        try:
            for part in assertion["path"].split("."):
                value = value[int(part)] if isinstance(value, list) else value[part]
            operation = assertion.get("op", "equals")
            target = assertion["value"]
            if operation == "equals":
                passed = type(value) is type(target) and value == target
            elif operation == "set_equals":
                passed = isinstance(value, list) and isinstance(target, list) and set(value) == set(target)
            else:
                passed = False
        except (KeyError, IndexError, ValueError, TypeError):
            passed = False
        outcomes.append(passed)
    fact_rules = expected.get("reply_assertions") or {}
    facts_checked = bool(fact_rules)
    facts_passed = facts_checked and all(text in answer for text in fact_rules.get("contains", [])) and all(
        text not in answer for text in fact_rules.get("excludes", []))
    return {
        "checked": bool(assertions and evidence_id and snapshot is not None),
        "passed": bool(assertions and evidence_id and all(outcomes)),
        "reply_checked": facts_checked,
        "reply_passed": facts_passed,
        "evidence_id": str(evidence_id),
    }
