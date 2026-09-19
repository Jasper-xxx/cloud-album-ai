import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


EVALUATION_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVALUATION_ROOT))

from cloud_album_eval.metrics import (  # noqa: E402
    EvaluationError,
    evaluate_agent,
    evaluate_async_tasks,
    evaluate_face_clustering,
    evaluate_image_search,
    evaluate_security,
    nearest_rank_percentile,
)
from cloud_album_eval.report import apply_gates, build_report, render_markdown  # noqa: E402


class MetricTests(unittest.TestCase):
    def test_nearest_rank_percentile(self):
        self.assertEqual(5, nearest_rank_percentile([1, 2, 3, 4, 5], 95))
        self.assertIsNone(nearest_rank_percentile([], 95))

    def test_image_search_macro_recall_and_latency(self):
        metrics = evaluate_image_search(
            [
                {
                    "case_id": "q1",
                    "relevant_ids": ["a", "b"],
                    "returned_ids": ["a", "x", "b"],
                    "latency_ms": 10,
                },
                {
                    "case_id": "q2",
                    "relevant_ids": ["c"],
                    "returned_ids": ["x", "c"],
                    "latency_ms": 20,
                },
            ]
        )
        self.assertEqual(1.0, metrics["recall_at_5"])
        self.assertEqual(20, metrics["latency_p95_ms"])

    def test_face_pair_counts_without_quadratic_enumeration(self):
        metrics = evaluate_face_clustering(
            [
                {"face_id": "1", "truth_person_id": "A", "predicted_cluster_id": "X", "corrected": False},
                {"face_id": "2", "truth_person_id": "A", "predicted_cluster_id": "X", "corrected": False},
                {"face_id": "3", "truth_person_id": "B", "predicted_cluster_id": "X", "corrected": True},
                {"face_id": "4", "truth_person_id": "B", "predicted_cluster_id": "Y", "corrected": False},
            ]
        )
        self.assertAlmostEqual(1 / 3, metrics["pairwise_precision"])
        self.assertAlmostEqual(1 / 2, metrics["pairwise_recall"])
        self.assertAlmostEqual(0.4, metrics["pairwise_f1"])
        self.assertAlmostEqual(2 / 3, metrics["wrong_merge_rate"])
        self.assertEqual(0.25, metrics["manual_correction_rate"])

    def test_null_face_predictions_are_independent_singletons(self):
        metrics = evaluate_face_clustering(
            [
                {"face_id": "1", "truth_person_id": "A", "predicted_cluster_id": None},
                {"face_id": "2", "truth_person_id": "B", "predicted_cluster_id": None},
            ]
        )
        self.assertEqual(0, metrics["pair_counts"]["predicted_positive"])
        self.assertIsNone(metrics["wrong_merge_rate"])

    def test_agent_uses_strict_tool_sequence_and_field_accuracy(self):
        metrics = evaluate_agent(
            [
                {
                    "case_id": "a1",
                    "expected": {"tool_calls": [{"name": "searchFiles", "arguments": {"searchType": "tag", "searchKeyword": "猫"}}]},
                    "actual": {
                        "tool_calls": [{"name": "searchFiles", "arguments": {"searchType": "tag", "searchKeyword": "猫"}}],
                        "completed": True,
                        "verification": {"source": "response_assertion", "passed": True},
                    },
                },
                {
                    "case_id": "a2",
                    "expected": {"tool_calls": [{"name": "previewTagAction", "arguments": {"action": "add_tags", "tagName": "宠物"}}]},
                    "actual": {
                        "tool_calls": [{"name": "previewTagAction", "arguments": {"action": "add_tags", "tagName": "动物"}}],
                        "completed": False,
                        "verification": {"source": "backend_state", "passed": True},
                    },
                },
            ]
        )
        self.assertEqual(1.0, metrics["tool_selection_accuracy"])
        self.assertEqual(0.75, metrics["parameter_extraction_accuracy"])
        self.assertEqual(0.5, metrics["parameter_case_exact_match_rate"])
        self.assertEqual(0.5, metrics["task_completion_rate"])

    def test_security_rates_are_split_by_attack_type(self):
        metrics = evaluate_security(
            [
                {"case_id": "s1", "attack_type": "unauthorized_access", "response_blocked": True, "side_effect_safe": True, "blocked": True},
                {"case_id": "s2", "attack_type": "unauthorized_access", "response_blocked": False, "side_effect_safe": True, "blocked": False},
                {"case_id": "s3", "attack_type": "confirmation_bypass", "response_blocked": True, "side_effect_safe": True, "blocked": True},
            ]
        )
        self.assertAlmostEqual(2 / 3, metrics["interception_rate"])
        self.assertEqual(0.5, metrics["interception_rate_by_attack_type"]["unauthorized_access"])
        self.assertEqual(["s2"], metrics["false_negative_case_ids"])

    def test_agent_rejects_truthy_string_booleans(self):
        with self.assertRaisesRegex(EvaluationError, "must be a boolean"):
            evaluate_agent(
                [
                    {
                        "case_id": "strict-agent",
                        "expected": {"tool_calls": []},
                        "actual": {
                            "tool_calls": [],
                            "completed": "false",
                            "verification": {
                                "source": "backend_state",
                                "passed": True,
                            },
                        },
                    }
                ]
            )

    def test_security_requires_response_and_side_effect_success(self):
        metrics = evaluate_security(
            [
                {
                    "case_id": "unsafe-effect",
                    "attack_type": "parameter_tampering",
                    "response_blocked": True,
                    "side_effect_safe": False,
                    "blocked": False,
                }
            ]
        )
        self.assertEqual(0.0, metrics["interception_rate"])
        self.assertEqual(0.0, metrics["side_effect_verification_rate"])
        self.assertEqual(["unsafe-effect"], metrics["unsafe_side_effect_case_ids"])

    def test_security_rejects_truthy_string_booleans(self):
        with self.assertRaisesRegex(EvaluationError, "must be a boolean"):
            evaluate_security(
                [
                    {
                        "case_id": "strict-security",
                        "attack_type": "unauthorized_access",
                        "response_blocked": "false",
                        "side_effect_safe": True,
                        "blocked": False,
                    }
                ]
            )

    def test_async_metrics_separate_replays_from_duplicate_effects(self):
        metrics = evaluate_async_tasks(
            [
                {"case_id": "t1", "final_status": "SUCCESS", "execution_count": 2, "business_effect_count": 1, "recovery_time_ms": 1000},
                {"case_id": "t2", "final_status": "FAILED", "execution_count": 3, "business_effect_count": 2, "recovery_time_ms": 2000},
            ]
        )
        self.assertEqual(0.5, metrics["eventual_success_rate"])
        self.assertEqual(3, metrics["duplicate_execution_count"])
        self.assertEqual(1, metrics["duplicate_business_effect_count"])
        self.assertEqual(2000, metrics["recovery_time_p95_ms"])

    def test_async_recovery_time_treats_naive_backend_time_as_local(self):
        injected_local = datetime(2026, 8, 5, 21, 0, 0).astimezone()
        terminal_local = (injected_local + timedelta(seconds=60)).replace(tzinfo=None)
        metrics = evaluate_async_tasks(
            [
                {
                    "case_id": "timezone",
                    "final_status": "SUCCESS",
                    "execution_count": 2,
                    "fault_injected_at": injected_local.astimezone(timezone.utc).isoformat(),
                    "terminal_at": terminal_local.isoformat(),
                }
            ]
        )
        self.assertEqual(60_000, metrics["recovery_time_p95_ms"])

    def test_report_gate_and_sample_warning(self):
        metrics = {"recall_at_5": 0.9}
        gates = apply_gates(metrics, {"recall_at_5": {"op": ">=", "value": 0.85}})
        self.assertTrue(gates[0]["passed"])
        manifest = {
            "project": "Cloud-Album",
            "run_name": "unit",
            "sample_data": True,
            "suites": {"image_search": {"thresholds": {"recall_at_5": 0.85}}},
        }
        report = build_report(manifest, {"image_search": metrics})
        markdown = render_markdown(report)
        self.assertIn("示例数据", markdown)
        self.assertIn("PASS", markdown)


if __name__ == "__main__":
    unittest.main()
