import inspect
import json
import unittest
import uuid
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]
NODES = {n["id"]: n["data"] for n in yaml.safe_load((ROOT / "docs/云忆相册助手-write.yml").read_text(encoding="utf-8"))["workflow"]["graph"]["nodes"]}


def execute(name, **kwargs):
    namespace = {}
    exec(compile(NODES[name]["code"], name, "exec"), namespace)
    return namespace["main"](**kwargs)


class DifyPythonNodesTest(unittest.TestCase):
    def test_restore_routes_to_p3_and_explicitly_resolves_recycle_images(self):
        raw = json.dumps(dict(mode="preview",family="p4_action",extendedAction="restore_files"))
        for query in ("把回收站的照片恢复", "恢复回收站里的全部图片"):
            result = execute("parse_write_action", raw=raw, current_query=query)
            self.assertEqual(("preview", "p3_action", True), tuple(result[k] for k in ("mode", "family", "allRecycleImages")))
            self.assertEqual([], result["fileIds"])
        for query in ("不要恢复回收站的照片", "恢复回收站里的猫照片", "恢复回收站最新两张照片"):
            result = execute("parse_write_action", raw=raw, current_query=query)
            self.assertFalse(result["allRecycleImages"])
            self.assertEqual("message", result["mode"])
        self.assertEqual(["parse_write_action", "allRecycleImages"], NODES["tool_preview_p3_action"]["tool_parameters"]["allRecycleImages"]["value"])

    def test_published_export_enables_single_supported_image_attachment(self):
        workflow = yaml.safe_load((ROOT / "docs/云忆相册助手-write.yml").read_text(encoding="utf-8"))
        upload = workflow["workflow"]["features"]["file_upload"]
        self.assertTrue(upload["enabled"])
        self.assertEqual(1, upload["number_limits"])
        self.assertEqual(["image"], upload["allowed_file_types"])
    def test_album_photo_delete_generates_recycle_preview_not_phantom_confirmation(self):
        for raw in ('{"mode":"none"}', '{"mode":"preview","family":"p4_action","extendedAction":"permanently_delete_files","fileIds":["invented"]}'):
            result = execute("parse_write_action", raw=raw, current_query="帮我把相册 测试旅行 里面的图片删掉")
            self.assertEqual(("preview", "p4_action", "move_files_to_recycle_bin", "测试旅行"),
                             tuple(result[k] for k in ("mode", "family", "extendedAction", "albumName")))
            self.assertEqual([], result["fileIds"])
            self.assertFalse(result["confirmed"])
        no_pending = execute("parse_write_action", raw="{}", current_query="确认")
        self.assertEqual("message", no_pending["mode"])
        confirmed = execute("parse_write_action", raw="{}", current_query="确认", pending_action_id="actual",
                            pending_confirmation_token="token", pending_idempotency_key="key", pending_family="p4_action")
        self.assertEqual("execute", confirmed["mode"])
        self.assertTrue(confirmed["confirmed"])

    def test_delete_guard_does_not_broaden_subset_or_negation(self):
        raw = json.dumps(dict(mode="preview",family="p4_action",extendedAction="move_files_to_recycle_bin",albumName="测试旅行"))
        for query in ("不要把相册 测试旅行 里面的图片删掉", "如何把相册 测试旅行 里面的图片删掉", "删除相册测试旅行里的最新两张照片", "删除相册测试旅行里的猫图片", "删除相册测试旅行和家庭里的图片"):
            self.assertNotEqual("preview", execute("parse_write_action", raw=raw, current_query=query)["mode"])

    def test_read_model_cannot_fabricate_write_confirmation(self):
        result = execute("validate_read_plan", raw=json.dumps({"selectedTool":"none","directReply":"请确认是否永久删除这些图片？"}), current_query="帮我把相册 测试旅行 里面的图片删掉")
        self.assertIn("尚未创建", result["directReply"])
        self.assertNotIn("永久删除", result["directReply"])

    def test_short_read_replies_cannot_execute_or_bypass_pending_ambiguity(self):
        # These tests exercise deterministic gates, not LLM history recovery.
        read_plan = json.dumps(dict(selectedTool="advancedSearchFiles", current=3,
                                    size=3, tagState="untagged"))
        pending = dict(pending_action_id="actual", pending_confirmation_token="token",
                       pending_idempotency_key="key", pending_family="album")
        for query in ("是", "是的", "好", "可以", "可以的", "嗯", "行", "同意", "ＯＫ。"):
            with self.subTest(query=query):
                write = execute("parse_write_action", raw='{"mode":"execute","family":"album"}',
                                current_query=query, **pending)
                self.assertNotEqual("execute", write["mode"])
                self.assertFalse(write["confirmed"])
                blocked = execute("validate_read_plan", raw=read_plan, current_query=query,
                                  pending_action_id="actual")
                self.assertEqual("none", blocked["selectedTool"])
                self.assertIn("刚才的预览", blocked["directReply"])
                self.assertNotIn("尚未创建", blocked["directReply"])
                approved = execute("validate_read_plan", raw=read_plan, current_query=query)
                self.assertEqual(("advancedSearchFiles", 3, 3, "untagged"),
                                 tuple(approved[k] for k in ("selectedTool", "current", "size", "tagState")))
        explicit_page = execute("validate_read_plan", raw=read_plan,
                                current_query="继续下一页", pending_action_id="actual")
        self.assertEqual("advancedSearchFiles", explicit_page["selectedTool"])

    def test_conversation_transport_is_visible_to_dify_operation_parser(self):
        api = yaml.safe_load((ROOT / "docs/dify-agent-openapi.yaml").read_text(encoding="utf-8"))
        for path, item in api["paths"].items():
            for method, operation in item.items():
                if method in ("get", "post", "put", "delete", "patch"):
                    with self.subTest(path=path, method=method):
                        parameter = next(p for p in operation.get("parameters", []) if p["name"] == "conversationId")
                        self.assertEqual("query", parameter["in"])
                        self.assertTrue(parameter["required"])

    def test_conversation_variable_ids_fit_dify_database_uuid_columns(self):
        workflow = yaml.safe_load((ROOT / "docs/云忆相册助手-write.yml").read_text(encoding="utf-8"))
        identifiers = []
        for variable in workflow["workflow"]["conversation_variables"]:
            with self.subTest(variable=variable["name"]):
                identifiers.append(str(uuid.UUID(variable["id"])))
        self.assertEqual(len(identifiers), len(set(identifiers)))

    def test_attachment_references_are_available_to_publish_checklist(self):
        workflow = yaml.safe_load((ROOT / "docs/云忆相册助手-write.yml").read_text(encoding="utf-8"))
        edges = workflow["workflow"]["graph"]["edges"]
        first = NODES["first_attachment"]
        # Dify web variable/utils.ts exposes no List Operator outputs without var_type.
        self.assertEqual("array[file]", first.get("var_type"))
        self.assertEqual("file", first.get("item_var_type"))
        self.assertEqual(["sys", "files"], first["variable"])
        for tool in ("tool_upload_attachment", "tool_search_attachment"):
            self.assertEqual(["first_attachment", "first_record"], NODES[tool]["tool_parameters"]["attachment"]["value"])
            ancestors, pending = set(), [tool]
            while pending:
                current = pending.pop()
                for edge in edges:
                    if edge["target"] == current and edge["source"] not in ancestors:
                        ancestors.add(edge["source"])
                        pending.append(edge["source"])
            self.assertIn("first_attachment", ancestors)

    def test_bad_preview_and_noop_preserve_previous_confirmation(self):
        old = dict(pending_action_id="old",pending_confirmation_token="secret",pending_idempotency_key="key",pending_family="album",pending_expires_at="later")
        for name in ("capture_pending_album","capture_pending_tag","capture_pending_p2","capture_pending_extended"):
            for raw in ('not json','{"code":500,"data":{}}','{"code":200,"data":{"requiresConfirmation":false}}'):
                result=execute(name,raw=raw,**old)
                self.assertEqual("old",result["pendingActionId"])
                self.assertEqual("secret",result["confirmationToken"])
                self.assertNotIn("secret",result["message"])

    def test_cancel_only_clears_after_confirmed_server_state(self):
        self.assertFalse(execute("render_cancel",raw='{"code":500,"data":{"status":"CANCELLED"}}')["terminal"])
        self.assertTrue(execute("render_cancel",raw='{"code":200,"data":{"status":"CANCELLED"}}')["terminal"])

    def test_attachment_only_saves_on_explicit_instruction(self):
        for query in ("不要保存附件", "保存附件然后删除全部", "保存附件再分享", "如何保存附件", "这张照片是什么", "以图搜图"):
            self.assertNotEqual("save",execute("attachment_intent",query=query)["mode"])
        self.assertEqual("save",execute("attachment_intent",query="保存附件")["mode"])
        self.assertEqual("search",execute("attachment_intent",query="以图搜图")["mode"])

    def test_attachment_result_uses_actual_returned_id(self):
        result=execute("render_attachment",raw='{"code":200,"data":{"uploaded":true,"fileId":"actual-file"}}')
        self.assertEqual("actual-file",result["fileId"])
        unknown=execute("render_attachment",raw='{"code":500,"data":{"uploaded":true,"fileId":"forged"}}',previous_file_id="old")
        self.assertEqual("old",unknown["fileId"])

    def test_all_python_nodes_compile(self):
        for name, data in NODES.items():
            if data["type"] == "code":
                compile(data["code"], name, "exec")

    def test_write_plan_rejects_structured_fields(self):
        for field in ("mode", "family", "albumAction", "tagAction", "extendedAction", "minConfidence"):
            for value in ([], {}, True):
                with self.subTest(field=field, value=value):
                    result = execute("parse_write_action", raw=json.dumps({field: value}))
                    self.assertEqual("message", result["mode"])

    def test_zero_confidence_is_preserved(self):
        result = execute("parse_write_action", raw='{"minConfidence": 0}')
        self.assertEqual(0, result["minConfidence"])

    def test_execution_unknown_never_claims_success_or_clears_credentials(self):
        for name in ("render_execute_album", "render_execute_tag", "render_execute_p2", "render_execute_extended"):
            for raw in ("not json", "[]", "{}", '{"code":500,"data":{"success":true}}', '{"code":200,"data":{}}', '{"code":200,"data":{"success":true,"affectedFileCount":[]}}'):
                with self.subTest(node=name, raw=raw):
                    result = execute(name, raw=raw)
                    self.assertFalse(result["terminal"])
                    self.assertNotIn("已完成", result["message"])

    def test_verified_success_displays_names_and_clickable_links(self):
        result = execute("render_execute_extended", raw=json.dumps({"code":200,"data":{"success":True,"resourceToken":"private-secret", "resourceUrl":"http://localhost/share/abc", "affectedFileCount":1,"affectedFiles":[{"fileId":"hidden-id","originFileName":"photo.jpg"}]}}))
        self.assertTrue(result["terminal"])
        self.assertIn("[打开分享或下载](http://localhost/share/abc)", result["message"])
        self.assertNotIn("private-secret", result["message"])
        self.assertNotIn("hidden-id", result["message"])
