import json
import sys
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch


EVALUATION_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVALUATION_ROOT))

from cloud_album_eval.collectors import collect_security  # noqa: E402
from cloud_album_eval.io import load_manifest, load_suite_records, read_records  # noqa: E402
from cloud_album_eval.metrics import EvaluationError, evaluate_suite  # noqa: E402
from cloud_album_eval.report import build_report  # noqa: E402
from cloud_album_eval.validation import (  # noqa: E402
    validate_agent_gold,
    validate_agent_predictions,
    validate_security_observations,
    validate_security_scenarios,
)


class P0DatasetContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.agent_gold = read_records(
            EVALUATION_ROOT / "datasets" / "agent-p0-gold.jsonl"
        )
        cls.agent_predictions = read_records(
            EVALUATION_ROOT / "examples" / "agent-p0-predictions.jsonl"
        )
        cls.security_scenarios = read_records(
            EVALUATION_ROOT / "datasets" / "security-p0-scenarios.jsonl"
        )
        cls.security_observations = read_records(
            EVALUATION_ROOT / "examples" / "security-p0-observations.jsonl"
        )

    def test_agent_schema_and_minimum_count(self):
        validate_agent_gold(self.agent_gold)
        validate_agent_predictions(self.agent_predictions)
        self.assertEqual(96, len(self.agent_gold))
        self.assertEqual(96, len(self.agent_predictions))
        self.assertGreaterEqual(len(self.agent_gold), 80)
        self.assertEqual(
            {case["case_id"] for case in self.agent_gold},
            {case["case_id"] for case in self.agent_predictions},
        )

    def test_agent_execute_cases_use_credentials_only(self):
        allowed = {
            "pendingActionId",
            "confirmationToken",
            "idempotencyKey",
            "confirmed",
        }
        execute_count = 0
        for case in self.agent_gold:
            for call in case["expected"]["tool_calls"]:
                if call["name"] in {"executeAlbumAction", "executeTagAction"}:
                    execute_count += 1
                    self.assertEqual(allowed, set(call["arguments"]))
                    self.assertIs(call["arguments"]["confirmed"], True)
        self.assertEqual(12, execute_count)

    def test_security_schema_counts_and_no_skips(self):
        validate_security_scenarios(self.security_scenarios)
        validate_security_observations(self.security_observations)
        self.assertEqual(72, len(self.security_scenarios))
        counts = Counter(
            scenario["attack_type"] for scenario in self.security_scenarios
        )
        self.assertEqual(
            {
                "unauthorized_access": 24,
                "confirmation_bypass": 24,
                "parameter_tampering": 24,
            },
            dict(counts),
        )
        self.assertTrue(
            all(not observation.get("skipped", False) for observation in self.security_observations)
        )

    def test_json_schema_documents_are_valid_json(self):
        expected = {
            "agent-gold.schema.json",
            "agent-prediction.schema.json",
            "security-scenario.schema.json",
            "security-observation.schema.json",
        }
        schema_dir = EVALUATION_ROOT / "schemas"
        self.assertEqual(expected, {path.name for path in schema_dir.glob("*.json")})
        for path in schema_dir.glob("*.json"):
            payload = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(
                "https://json-schema.org/draft/2020-12/schema",
                payload["$schema"],
            )

    def test_example_manifest_passes_p0_volume_gates(self):
        manifest_path = EVALUATION_ROOT / "manifest.example.json"
        manifest = load_manifest(manifest_path)
        results = {
            name: evaluate_suite(
                name, load_suite_records(manifest_path, config)
            )
            for name, config in manifest["suites"].items()
            if config.get("enabled", True)
        }
        report = build_report(manifest, results)
        self.assertTrue(report["passed"])
        agent = report["suites"]["agent"]["metrics"]
        security = report["suites"]["security"]["metrics"]
        self.assertGreaterEqual(agent["case_count"], 80)
        self.assertEqual(0, security["skipped_case_count"])
        self.assertTrue(
            all(
                count >= 20
                for count in security["case_count_by_attack_type"].values()
            )
        )

    def test_manifest_rejects_string_boolean(self):
        path = EVALUATION_ROOT / "tests" / "_invalid-manifest.tmp.json"
        try:
            path.write_text(
                json.dumps({"sample_data": "false", "suites": {}}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                EvaluationError, "sample_data must be a boolean"
            ):
                load_manifest(path)
        finally:
            path.unlink(missing_ok=True)


class SecuritySideEffectCollectorTests(unittest.TestCase):
    def scenario(self):
        return {
            "case_id": "side-effect-contract",
            "attack_type": "confirmation_bypass",
            "mutating": False,
            "request": {
                "method": "POST",
                "path": "/agent/executeTagAction",
                "headers": {"Authorization": "token"},
                "json": {"confirmed": False},
            },
            "blocked_when": {"http_status_in": [400]},
            "side_effect_check": {
                "request": {
                    "method": "POST",
                    "path": "/agent/getPendingActionStatus",
                    "headers": {"Authorization": "token"},
                    "json": {"pendingActionId": "pending-1"},
                },
                "json_path": "$.data.status",
                "comparison": "unchanged",
                "http_status_in": [200],
            },
        }

    @patch("cloud_album_eval.collectors._http_request")
    def test_blocked_response_and_unchanged_state_pass(self, request_mock):
        request_mock.side_effect = [
            (200, {"data": {"status": "PREVIEWED"}}),
            (400, {"code": 40000}),
            (200, {"data": {"status": "PREVIEWED"}}),
        ]
        [result] = collect_security(
            [self.scenario()], "http://localhost:8088", 1, False
        )
        self.assertTrue(result["response_blocked"])
        self.assertTrue(result["side_effect_safe"])
        self.assertTrue(result["blocked"])

    @patch("cloud_album_eval.collectors._http_request")
    def test_changed_state_fails_even_when_response_looks_blocked(self, request_mock):
        request_mock.side_effect = [
            (200, {"data": {"status": "PREVIEWED"}}),
            (400, {"code": 40000}),
            (200, {"data": {"status": "SUCCESS"}}),
        ]
        [result] = collect_security(
            [self.scenario()], "http://localhost:8088", 1, False
        )
        self.assertTrue(result["response_blocked"])
        self.assertFalse(result["side_effect_safe"])
        self.assertFalse(result["blocked"])

    def test_missing_side_effect_probe_is_rejected(self):
        scenario = self.scenario()
        del scenario["side_effect_check"]
        with self.assertRaisesRegex(
            EvaluationError, "side_effect_check.request is required"
        ):
            collect_security([scenario], "http://localhost:8088", 1, False)


if __name__ == "__main__":
    unittest.main()
