"""Phase 2 contracts. Fixtures make no model or business calls.

Executed during the focused phase 2 acceptance on 2026-10-04.
"""
from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts/dify"))
from read_write_bridge import bridge_entry, bridge_preview
from read_loop_state import READ_LOOP_CONFIG, initialize, prepare, authorize, observe, observe_failure
from render_preview import main as render_preview


def entry(query, mode="none", **configuration):
    cfg = dict(READ_LOOP_CONFIG, writePreviewEnabled=True)
    cfg.update(configuration)
    return bridge_entry(query, mode, json.dumps(cfg))


def begin(query, **configuration):
    parsed = entry(query, **configuration)
    assert parsed["mode"] == "need_scope"
    cfg = dict(READ_LOOP_CONFIG, writePreviewEnabled=True)
    cfg.update(configuration)
    return initialize(parsed["scopeQuery"], json.dumps(cfg), 0, parsed["bridge"])["state"]


def call(state, **overrides):
    result = prepare(state)
    prepared = result["state"]
    public = json.loads(result["plannerInput"])
    raw = deepcopy(public["scopeContract"]["requiredPlan"])
    raw.update(overrides)
    return authorize(json.dumps(raw, ensure_ascii=False), prepared)


def albums(names=("测试旅行",), total=None):
    total = len(names) if total is None else total
    return {"code": 200, "data": {"current": 1, "size": 20, "total": total, "pages": (total + 19) // 20,
            "records": [{"albumId": 101 + i, "albumName": name, "type": "normal"} for i, name in enumerate(names)]}}


def photos(approved, total=1, ids=None):
    current, size = approved["current"], approved["size"]
    count = max(0, min(size, total - (current - 1) * size))
    ids = ids if ids is not None else ["file_" + str((current - 1) * size + i) for i in range(count)]
    conditions = ["媒体：picture"]
    if approved.get("city"):
        conditions.append("城市：" + approved["city"])
    if approved.get("tagState") != "all":
        conditions.append("标签状态：" + approved["tagState"])
    if approved.get("tags"):
        conditions.append("标签OR：" + "、".join(approved["tags"]))
    if approved.get("albumId", -1) != -1:
        conditions.append("普通相册#" + str(approved["albumId"]))
    if approved.get("dateFrom"):
        conditions.append(("拍摄" if approved["dateField"] == "taken" else "上传") + "时间范围")
    return {"code": 200, "data": {"current": current, "size": size, "total": total,
            "pages": (total + size - 1) // size, "hasNext": current * size < total,
            "conditionSummary": "；".join(conditions), "relaxSuggestions": [],
            "records": [{"fileId": identifier, "originFileName": "测试.jpg", "tags": []} for identifier in ids]}}


def album_photos(query="先查找北京未标签的照片，再整理到相册「测试旅行」", total=1, names=("测试旅行",)):
    first = call(begin(query))
    listed = observe(first["state"], json.dumps(albums(names)))
    approved = call(listed["state"])
    return observe(approved["state"], json.dumps(photos(approved, total))), approved


class ReadWriteBridgeContract(unittest.TestCase):
    def test_current_input_separates_write_tag_from_source_filter(self):
        parsed = entry("先查找北京未标签的照片，再添加标签「旅行」")
        frozen = json.loads(parsed["bridge"])
        self.assertEqual("need_scope", parsed["mode"])
        self.assertEqual(("tag", "add_tags", "旅行", "all"), (frozen["family"], frozen["action"], frozen["target"], frozen["rangeMode"]))
        self.assertNotIn("旅行", parsed["scopeQuery"])
        self.assertNotIn("tags", frozen["parameters"])

    def test_ordinary_search_and_deterministic_controls_do_not_bridge(self):
        for query, mode in (("查找北京未标签的照片", "none"), ("确认", "execute"), ("取消", "cancel"), ("状态", "status"), ("好", "none"), ("把最新5张照片整理到相册「测试旅行」", "preview"), ("创建空相册「测试旅行」", "preview")):
            with self.subTest(query=query):
                self.assertEqual("legacy", entry(query, mode)["mode"])
                self.assertEqual("", entry(query, mode)["bridge"])

    def test_unknown_scope_negation_history_and_multi_actions_never_preview(self):
        for query in ("先查询刚才那些照片，再添加标签「旅行」", "不要把北京未标签的照片整理到相册「测试旅行」", "先查询北京最清晰的照片，再整理到相册「测试旅行」", "先查找北京照片，再添加标签「旅行」并移除标签「旧标签」", "先查找所有照片，前5张，再添加标签「旅行」", "先查找北京照片，再整理到人物相册「旅行」"):
            with self.subTest(query=query):
                self.assertEqual("message", entry(query)["mode"])
                self.assertEqual("", entry(query)["bridge"])

    def test_switches_fail_closed_for_compound_write_requests(self):
        for config in ({"enabled": False}, {"writePreviewEnabled": False}):
            self.assertEqual("message", entry("先查找北京照片，再添加标签「旅行」", **config)["mode"])

    def test_unique_existing_album_is_code_resolved_and_real_ids_are_native(self):
        done, approved = album_photos()
        preview = bridge_preview(done["state"])
        self.assertEqual(("album", "add_files_to_album", 101, ["file_0"]), (preview["family"], preview["action"], preview["albumId"], preview["fileIds"]))
        self.assertEqual("", preview["searchType"])
        self.assertNotIn("confirmed", preview)
        self.assertNotIn("confirmationToken", done["state"])
        self.assertEqual((2, 2, 3), tuple(json.loads(done["state"])[key] for key in ("step", "toolCalls", "llmCalls")))

    def test_absent_destination_is_proven_by_complete_list_then_create_preview(self):
        done, _ = album_photos(names=())
        self.assertEqual("create_album_and_add_files", bridge_preview(done["state"])["action"])

    def test_destination_duplicates_or_incomplete_list_stop_before_photos(self):
        for page in (albums(("测试旅行", "测试旅行")), albums(tuple("相册" + str(i) for i in range(20)), total=21)):
            approved = call(begin("先查找北京照片，再整理到相册「测试旅行」"))
            result = observe(approved["state"], json.dumps(page))
            self.assertEqual("NEEDS_CLARIFICATION", result["status"])
            self.assertEqual("none", bridge_preview(result["state"])["family"])
            self.assertEqual(1, json.loads(result["state"])["toolCalls"])

    def test_frozen_scope_rejects_missing_filters_wrong_tool_and_unknown_ids(self):
        state = begin("先查找北京未标签的照片，再添加标签「旅行」")
        for overrides in ({"parameters": {"fileIds": ["made_up"]}}, {"selectedTool": "previewTagAction"}, {"parameters": {"city": "上海"}}, {"task": {"goals": ["search_photos"], "constraints": {"tool": "advancedSearchFiles", "parameters": {"tagState": "untagged"}}}}):
            result = call(state, **overrides)
            self.assertEqual("none", result["selectedTool"])
            self.assertEqual(0, json.loads(result["state"])["toolCalls"])

    def test_all_pages_are_required_and_first_n_is_an_explicit_cap(self):
        done, approved = album_photos(total=25)
        self.assertEqual("RUNNING", done["status"])
        self.assertEqual("none", bridge_preview(done["state"])["family"])
        second = call(done["state"])
        completed = observe(second["state"], json.dumps(photos(second, total=25)))
        self.assertEqual(25, len(bridge_preview(completed["state"])["fileIds"]))
        capped, _ = album_photos("先查找北京未标签的照片，前5张，再整理到相册「测试旅行」", total=25)
        self.assertEqual(5, len(bridge_preview(capped["state"])["fileIds"]))

    def test_budget_partial_failure_zero_and_truncation_do_not_preview(self):
        pending, approved = album_photos(total=41)
        second = call(pending["state"])
        stopped = observe(second["state"], json.dumps(photos(second, total=41)))
        self.assertEqual("BUDGET_EXCEEDED", stopped["status"])
        self.assertEqual("none", bridge_preview(stopped["state"])["family"])
        self.assertEqual("none", bridge_preview(observe_failure(second["state"], "HTTP 403")["state"])["family"])
        empty, _ = album_photos(total=0)
        self.assertEqual("NO_RESULTS", empty["status"])
        self.assertEqual("none", bridge_preview(empty["state"])["family"])
        small = begin("先查找北京未标签的照片，再添加标签「旅行」", maxObservationChars=512)
        call_small = call(small)
        cut = observe(call_small["state"], json.dumps(photos(call_small, total=20)))
        self.assertEqual("none", bridge_preview(cut["state"])["family"])

    def test_page_total_drift_duplicates_and_unknown_provenance_are_rejected(self):
        pending, _ = album_photos(total=25)
        next_call = call(pending["state"])
        for page in (photos(next_call, total=26), photos(next_call, total=25, ids=["file_0"] + ["file_" + str(i) for i in range(21, 25)])):
            stopped = observe(next_call["state"], json.dumps(page))
            self.assertEqual("none", bridge_preview(stopped["state"])["family"])
        done, _ = album_photos()
        for mutate in (lambda s: s["evidenceMap"].pop(s["bridge"]["photoRefs"][0]), lambda s: s["observations"][-1].update(ok=False), lambda s: s["bridge"]["photoRefs"].append("unknown"), lambda s: s.update(startedAt=1)):
            state = json.loads(done["state"])
            mutate(state)
            self.assertEqual("none", bridge_preview(json.dumps(state))["family"])

    def test_remove_tag_uses_source_tag_filter_and_target_from_current_message(self):
        initial = begin("先查找标签「旧标签」的照片，再移除标签「旧标签」")
        approved = call(initial)
        done = observe(approved["state"], json.dumps(photos(approved)))
        preview = bridge_preview(done["state"])
        self.assertEqual(("tag", "remove_tags", "旧标签", ["file_0"]), (preview["family"], preview["action"], preview["tagName"], preview["fileIds"]))

    def test_source_album_and_destination_are_distinct_verified_resources(self):
        state = begin("先查找相册「源相册」里未标签的照片，再整理到相册「测试旅行」")
        first = call(state)
        listed = observe(first["state"], json.dumps(albums(("测试旅行", "源相册"))))
        self.assertEqual(["resolve_destination", "resolve_album"], json.loads(listed["state"])["completedGoals"])
        photo_call = call(listed["state"])
        self.assertEqual(102, photo_call["albumId"])
        done = observe(photo_call["state"], json.dumps(photos(photo_call)))
        self.assertEqual(101, bridge_preview(done["state"])["albumId"])
        self.assertEqual(2, json.loads(done["state"])["toolCalls"])

    def test_relative_dates_are_code_frozen_and_cannot_be_replaced(self):
        state = json.loads(begin("先查找去年北京未标签的照片，再添加标签「旅行」"))
        params = state["bridge"]["expectedTask"]["constraints"]["parameters"]
        year = int(state["referenceDate"][:4]) - 1
        self.assertEqual((str(year) + "-01-01", str(year) + "-12-31"), (params["dateFrom"], params["dateTo"]))
        changed = dict(params, dateFrom="2000-01-01")
        self.assertEqual("none", call(json.dumps(state), parameters=changed)["selectedTool"])

    def test_quoted_selector_names_do_not_add_unrequested_filters(self):
        for query, key, value in (("先查找关键词「去年」的照片，再添加标签「旅行」", "keyword", "去年"),
                                  ("先查找标签「未标签」的照片，再添加标签「旅行」", "tags", ["未标签"]),
                                  ("先查找标签「北京」的照片，再添加标签「旅行」", "tags", ["北京"])):
            data = json.loads(begin(query))
            params = data["bridge"]["expectedTask"]["constraints"]["parameters"]
            self.assertEqual(value, params[key])
            self.assertEqual(("", "", "all"), (params["city"], params["dateFrom"], params["tagState"]))

    def test_failed_and_no_change_preview_preserve_original_pending(self):
        old = {"pending_action_id": "original", "pending_confirmation_token": "token", "pending_idempotency_key": "key", "pending_family": "album", "pending_expires_at": "expiry"}
        for response in ({"code": 403}, {"code": 200, "data": {"requiresConfirmation": False, "summary": "没有变化"}}):
            result = render_preview(json.dumps(response), **old, expected_family="tag")
            self.assertEqual(("original", "token", "key", "album", "expiry"), tuple(result[key] for key in ("pendingActionId", "confirmationToken", "idempotencyKey", "family", "expiresAt")))

    def test_preview_and_execute_are_outside_the_loop_with_exact_receivers(self):
        import yaml
        root = Path(__file__).resolve().parents[2]
        graph = yaml.safe_load((root / "docs/云忆相册助手-write.yml").read_text(encoding="utf-8"))["workflow"]["graph"]
        nodes = {node["id"]: node for node in graph["nodes"]}
        children = {key for key, node in nodes.items() if node.get("parentId") == "read_loop"}
        for edge in graph["edges"]:
            if edge["source"] in children:
                self.assertIn(edge["target"], children)
        for family in ("album", "tag"):
            tool = nodes["read_loop_preview_" + family]
            self.assertNotIn("parentId", tool)
            self.assertFalse(tool["data"]["retry_config"]["retry_enabled"])
            self.assertEqual({"type": "variable", "value": ["sys", "conversation_id"]}, tool["data"]["tool_parameters"]["conversationId"])
            self.assertEqual({"type": "variable", "value": ["read_loop_preview_request", "fileIds"]}, tool["data"]["tool_parameters"]["fileIds"])
            self.assertNotIn("confirmed", tool["data"]["tool_parameters"])
