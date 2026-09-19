import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_album_eval.collectors import build_dify_agent_predictions
from cloud_album_eval.business import verify_business


class BusinessEvidenceTest(unittest.TestCase):
    def predict(self, rows, observation=None):
        gold = [{"case_id": "one", "expected": {"tool_calls": [{"name": "searchFiles", "arguments": {"searchType": "location"}}],
                "business_assertions": [{"path": "ids", "op": "set_equals", "value": ["owned-photo"]}],
                "reply_assertions": {"contains": ["找到"], "excludes": ["已删除"]}}}]
        runs = [{"case_id": "one", "workflow_run_id": "run", "status": "succeeded", "answer": "找到一张照片", "business_observation": observation}]
        with patch("cloud_album_eval.collectors._load_dify_traces", return_value={"run": rows}):
            return build_dify_agent_predictions(gold, runs, "unused", "unused", "unused")[0]["actual"]

    def row(self):
        return {"node_id": "tool_search_tag", "node_type": "tool", "status": "succeeded", "inputs": {"searchType": "location", "size": 10},
                "outputs": {"json": [{"code": 200, "data": []}]}}

    def test_successful_workflow_without_tool_is_not_completed(self):
        result = self.predict([])
        self.assertFalse(result["completed"])
        self.assertFalse(result["verification"]["passed"])

    def test_tool_success_without_business_evidence_is_not_completed(self):
        result = self.predict([self.row()])
        self.assertFalse(result["completed"])
        self.assertEqual({"searchType": "location", "size": 10}, result["tool_calls"][0]["arguments"])

    def test_wrong_actual_arguments_are_not_rebuilt_from_gold(self):
        row = self.row()
        row["inputs"]["searchType"] = "tag"
        result = self.predict([row], {"evidence_id": "readback-1", "snapshot": {"ids": ["owned-photo"]}})
        self.assertFalse(result["completed"])
        self.assertEqual("tag", result["tool_calls"][0]["arguments"]["searchType"])

    def test_independent_readback_and_reply_allow_completion(self):
        result = self.predict([self.row()], {"evidence_id": "readback-1", "snapshot": {"ids": ["owned-photo"]}})
        self.assertTrue(result["completed"])

    def test_incorrect_business_state_or_reply_fails(self):
        result = self.predict([self.row()], {"evidence_id": "readback-1", "snapshot": {"ids": ["foreign-photo"]}})
        self.assertFalse(result["completed"])
        self.assertFalse(verify_business({"reply_assertions": {"contains": ["找到"]}}, {}, "已删除")["reply_passed"])
