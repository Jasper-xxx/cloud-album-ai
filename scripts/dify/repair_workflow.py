"""Apply audited transport/guard changes while preserving untouched YAML nodes."""
from copy import deepcopy
from pathlib import Path
import re
import yaml

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / "docs/云忆相册助手-write.yml"
source = path.read_text(encoding="utf-8")
workflow = yaml.safe_load(source)
graph = workflow["workflow"]["graph"]
original = deepcopy(graph)
nodes = {node["id"]: node for node in graph["nodes"]}

for node in graph["nodes"]:
    data = node["data"]
    if data["type"] == "tool":
        data.setdefault("tool_parameters", {})["conversationId"] = {"type": "variable", "value": ["sys", "conversation_id"]}
    if data["type"] == "code":
        code = data["code"]
        # Validate model field types before membership tests or normalization.
        if node["id"] == "parse_write_action":
            code = code.replace('                  data.update(obj)', '                  data.update(obj)')
            anchor = '    if data["mode"] not in '
            validation = '''    baseline = defaults()
    for key, default in baseline.items():
        value = data.get(key)
        if value is None:
            data[key] = default
        elif (isinstance(default, str) and not isinstance(value, str)) or (isinstance(default, list) and not isinstance(value, list)) or (isinstance(default, bool) and not isinstance(value, bool)) or (type(default) in (int, float) and (type(value) not in (int, float) or not __import__("math").isfinite(value))):
            data = defaults()
            data.update(mode="message", reason="参数类型无效，请重新说明要处理的照片和操作。")
            return data

'''
            if 'baseline = defaults()' not in code:
                code = code.replace(anchor, validation + anchor, 1)
            code = code.replace('float(data.get("minConfidence") or 0.5)', 'float(0.5 if data.get("minConfidence") is None else data["minConfidence"])')
        if node["id"] == "validate_read_plan":
            # Reject structured enum/text values before set membership. Lists remain lists.
            marker = '    if defaults["selectedTool"]'
            idx = code.find(marker)
            if idx >= 0 and 'Invalid model field types' not in code:
                code = code[:idx] + '''    # Invalid model field types must not reach enum membership operations.
    for key in ["selectedTool", "orderType", "orderKeyword", "imageTypeText", "locationLevel", "tagFilter", "searchType", "dateField", "tagOperator", "tagState", "orderBy"]:
        if not isinstance(defaults.get(key, ""), str):
            defaults[key] = ""
            defaults["selectedTool"] = "none"
            defaults["directReply"] = "查询参数类型无效，请重新说明筛选条件。"
''' + code[idx:]
        # Display file names; identifiers remain in structured tool outputs.
        code = re.sub(r'（(?:文件ID|fileId)：\{file_id\}）', '', code)
        data["code"] = code

for name in ("clear_pending_before_preview", "clear_pending_on_write_error"):
    data = nodes[name]["data"]
    data["title"] = "保留确认凭据直到服务端状态明确"
    for item in data["items"]:
        item["value"] = item["variable_selector"]

render_code = (ROOT / "scripts/dify/render_execution.py").read_text(encoding="utf-8")
for name in ("render_execute_album", "render_execute_tag", "render_execute_p2", "render_execute_extended", "render_cancel"):
    data = nodes[name]["data"]
    data["code"] = (ROOT / "scripts/dify/render_cancel.py").read_text(encoding="utf-8") if name == "render_cancel" else render_code
    data["outputs"] = {key: {"children": None, "type": "boolean" if key == "terminal" else "string"}
                       for key in ("publicMessage", "message", "terminal", "agentTaskId", "empty")}
    gate_id = name + "_verified"
    if gate_id in nodes:
        continue
    gate = deepcopy(nodes["route_status_terminal"])
    gate["id"] = gate_id
    gate["data"]["title"] = "明确成功才清理凭据"
    gate["data"]["cases"][0]["conditions"][0]["variable_selector"] = [name, "terminal"]
    graph["nodes"].append(gate)
    for edge in list(graph["edges"]):
        if edge["source"] != name:
            continue
        target = edge["target"]
        edge["target"] = gate_id
        edge["id"] = name + "-" + gate_id
        edge["data"]["targetType"] = "if-else"
        for handle, destination in (("true", target), ("false", "tool_get_pending_action_status")):
            new = deepcopy(edge)
            new.update(id=gate_id + "-" + handle, source=gate_id, sourceHandle=handle, target=destination)
            new["data"].update(sourceType="if-else", targetType=nodes[destination]["data"]["type"])
            graph["edges"].append(new)

for name in ("capture_pending_album", "capture_pending_tag", "capture_pending_p2", "capture_pending_extended"):
    data = nodes[name]["data"]
    data["code"] = (ROOT / "scripts/dify/render_preview.py").read_text(encoding="utf-8")
    data["outputs"] = {key: {"children": None, "type": "string"} for key in ("pendingActionId", "confirmationToken", "idempotencyKey", "family", "expiresAt", "message", "publicMessage")}
    data["variables"] = [v for v in data["variables"] if v["variable"] == "raw"] + [
        {"variable": key, "value_selector": ["conversation", key]} for key in ("pending_action_id", "pending_confirmation_token", "pending_idempotency_key", "pending_family", "pending_expires_at")
    ] + [{"variable": "expected_family", "value_selector": ["parse_write_action", "family"]}]

# Guard status parser against malformed/error envelopes and counters.
status = nodes["render_pending_status"]["data"]
status["code"] = status["code"].replace('data = envelope.get("data") if isinstance(envelope.get("data"), dict) else envelope',
    'data = envelope.get("data") if envelope.get("code") == 200 and isinstance(envelope.get("data"), dict) else {}')
status["code"] = re.sub(r'int\(data.get\("([^"]+)"\)\) or 0\)', r'int(data.get("\1") or 0)', status["code"])
status["code"] = status["code"].replace('    status = str(', '    if any(type(data.get(k, 0)) is not int for k in ("actualAffectedFileCount", "actualCreatedAlbumCount", "skippedFileCount")):\n        data = {}\n    status = str(') if '    if any(type(data.get' not in status["code"] else status["code"]

# Explicit attachment commands bypass model planning. A query never saves its upload.
if "route_attachments" not in nodes:
    def add_node(identifier, data):
        node = {"id": identifier, "data": data, "type": "custom", "width": 241, "height": 90,
                "position": {"x": -650, "y": len(graph["nodes"]) * 8}, "sourcePosition": "right", "targetPosition": "left"}
        graph["nodes"].append(node)
        nodes[identifier] = node

    def edge(source_id, target_id, handle="source"):
        graph["edges"].append({"id": source_id + "-" + handle + "-" + target_id, "source": source_id,
            "target": target_id, "sourceHandle": handle, "targetHandle": "target", "type": "custom",
            "data": {"sourceType": nodes[source_id]["data"]["type"], "targetType": nodes[target_id]["data"]["type"]}})

    def case(identifier, selector, value, vartype="string", operator="is"):
        return {"id": identifier, "case_id": identifier, "logical_operator": "and", "conditions": [
            {"id": identifier, "comparison_operator": operator, "variable_selector": selector, "value": value, "varType": vartype}]}

    add_node("route_attachments", {"type": "if-else", "title": "聊天是否带附件", "cases": [case("true", ["sys", "files"], "", "array[file]", "not empty")]})
    add_node("first_attachment", {"type": "list-operator", "title": "提取本次附件", "variable": ["sys", "files"],
            "filter_by": {"enabled": False, "conditions": []}, "order_by": {"enabled": False, "key": "", "value": "asc"},
            "limit": {"enabled": True, "size": 1}, "extract_by": {"enabled": False, "serial": "1"}})
    add_node("attachment_intent", {"type": "code", "title": "区分查询和保存授权", "code_language": "python3",
            "code": (ROOT / "scripts/dify/attachment_intent.py").read_text(encoding="utf-8"),
            "outputs": {"mode": {"type": "string", "children": None}}, "variables": [{"variable": "query", "value_selector": ["sys", "query"]}]})
    add_node("route_attachment_intent", {"type": "if-else", "title": "附件用途", "cases": [case("save", ["attachment_intent", "mode"], "save"), case("search", ["attachment_intent", "mode"], "search")]})
    for identifier, tool in (("tool_upload_attachment", "uploadAttachment"), ("tool_search_attachment", "searchByAttachment")):
        data = deepcopy(nodes["tool_search_tag"]["data"])
        data.update(tool_name=tool, title="保存附件到图库" if tool == "uploadAttachment" else "附件仅用于以图搜图", tool_label=tool,
                    tool_parameters={"attachment": {"type": "variable", "value": ["first_attachment", "first_record"]},
                                     "conversationId": {"type": "variable", "value": ["sys", "conversation_id"]}},
                    error_strategy="fail-branch")
        data.pop("paramSchemas", None)
        data.pop("params", None)
        add_node(identifier, data)
    add_node("attachment_tool_result", {"type": "variable-aggregator", "title": "附件返回结果", "output_type": "string",
            "variables": [["tool_upload_attachment", "text"], ["tool_search_attachment", "text"]]})
    add_node("render_attachment", {"type": "code", "title": "验证附件真实返回", "code_language": "python3",
            "code": (ROOT / "scripts/dify/attachment_result.py").read_text(encoding="utf-8"),
            "outputs": {k: {"type": "string", "children": None} for k in ("message", "fileId")},
            "variables": [{"variable": "raw", "value_selector": ["attachment_tool_result", "output"]},
                          {"variable": "previous_file_id", "value_selector": ["conversation", "attachment_file_id"]}]})
    add_node("remember_attachment", {"type": "assigner", "title": "保存真实附件标识", "version": "2", "items": [
            {"input_type": "variable", "operation": "over-write", "write_mode": "over-write", "value": ["render_attachment", "fileId"], "variable_selector": ["conversation", "attachment_file_id"]}]})
    add_node("answer_attachment", {"type": "answer", "title": "附件处理结果", "answer": "{{#render_attachment.message#}}", "variables": []})
    add_node("answer_attachment_help", {"type": "answer", "title": "明确附件用途", "answer": "每次处理一个附件。请携带附件发送「以图搜图」仅查询；发送「保存附件」授权保存到图库。", "variables": []})
    add_node("answer_attachment_failure", {"type": "answer", "title": "附件状态未知", "answer": "附件处理未确认成功。查询可以重试；保存请先检查图库，避免重复提交。", "variables": []})
    for e in graph["edges"]:
        if e["source"] == "start":
            e["target"] = "route_attachments"
            e["data"]["targetType"] = "if-else"
    edge("route_attachments", "extract_write_action", "false")
    edge("route_attachments", "first_attachment", "true")
    edge("first_attachment", "attachment_intent")
    edge("attachment_intent", "route_attachment_intent")
    edge("route_attachment_intent", "tool_upload_attachment", "save")
    edge("route_attachment_intent", "tool_search_attachment", "search")
    edge("route_attachment_intent", "answer_attachment_help", "false")
    for tool in ("tool_upload_attachment", "tool_search_attachment"):
        edge(tool, "attachment_tool_result")
        edge(tool, "answer_attachment_failure", "fail-branch")
    edge("attachment_tool_result", "render_attachment")
    edge("render_attachment", "remember_attachment")
    edge("remember_attachment", "answer_attachment")
    prompt = nodes["extract_write_action"]["data"]["prompt_template"]
    prompt[-1]["text"] += "\nLatest explicitly saved attachment fileId: {{#conversation.attachment_file_id#}}. Use this ID only when the user refers to that saved attachment."

# Refresh attachment behavior in workflows that already contain these nodes.
nodes["attachment_intent"]["data"]["code"] = (ROOT / "scripts/dify/attachment_intent.py").read_text(encoding="utf-8")
nodes["answer_attachment_help"]["data"]["answer"] = (
    "我可以直接用这张附件查找图库中的相似图片，也可以把附件保存到图库。"
    "请明确说「查找和这张图相似的图片」或「保存这张图片」。"
    "每次处理一个附件；查询不会保存图片，同时要求保存和查询时请分两次发送。"
)

# Dify's publish checklist derives List Operator outputs from these cached types.
# The backend schema accepts their absence, but the editor then exposes no outputs.
nodes["first_attachment"]["data"].update(var_type="array[file]", item_var_type="file")

from repair_recycle_flow import apply as repair_recycle_flow
repair_recycle_flow(nodes)

class Dumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True

def represent_string(dumper, value):
    return dumper.represent_scalar("tag:yaml.org,2002:str", value, style="|" if "\n" in value else None)

Dumper.add_representer(str, represent_string)

def dump_node(node):
    return "".join("    " + line if line.strip() else line for line in yaml.dump([node], Dumper=Dumper, allow_unicode=True, sort_keys=False, width=110).splitlines(True))

def child(node, key):
    return next(value for k, value in node.value if k.value == key)

root = yaml.compose(source)
graph_ast = child(child(root, "workflow"), "graph")
node_asts = child(graph_ast, "nodes").value
edits = []
for index, old in enumerate(original["nodes"]):
    new = nodes[old["id"]]
    if old != new:
        start = source.rfind("\n", 0, node_asts[index].start_mark.index) + 1
        end = source.rfind("\n", 0, node_asts[index].end_mark.index) + 1
        edits.append((start, end, dump_node(new)))
new_nodes = graph["nodes"][len(original["nodes"]):]
if new_nodes:
    end = source.rfind("\n", 0, node_asts[-1].end_mark.index) + 1
    edits.append((end, end, "".join(dump_node(n) for n in new_nodes)))
edges_ast = child(graph_ast, "edges")
start = source.rfind("\n", 0, edges_ast.start_mark.index) + 1
end = source.rfind("\n", 0, edges_ast.end_mark.index) + 1
edits.append((start, end, "".join(dump_node(edge) for edge in graph["edges"])))
for start, end, value in sorted(edits, reverse=True):
    source = source[:start] + value + source[end:]
assert yaml.safe_load(source)["workflow"]["graph"] == graph
if "name: attachment_file_id" not in source:
    source = source.replace("  environment_variables:", "  - description: 最近一次明确保存的附件标识\n    id: 6d9c5b1e-31b4-4f9d-bb6c-3fa2e80b1d92\n    name: attachment_file_id\n    selector: [conversation, attachment_file_id]\n    value: ''\n    value_type: string\n  environment_variables:", 1)
source = source.replace("id: ca-attachment-file-id", "id: 6d9c5b1e-31b4-4f9d-bb6c-3fa2e80b1d92")
source = source.replace("      number_limits: 3", "      number_limits: 1").replace("        number_limits: 3", "        number_limits: 1")
from repair_recycle_flow import enable_image_attachments
features = yaml.safe_load(source)["workflow"]["features"]
enable_image_attachments(features)
upload_block = yaml.dump({"file_upload": features["file_upload"]}, Dumper=Dumper, allow_unicode=True, sort_keys=False)
upload_block = ''.join('    ' + line for line in upload_block.splitlines(True))
source = re.sub(r'(?ms)^    file_upload:\n.*?(?=^    \w)', lambda _: upload_block, source, count=1)
assert yaml.safe_load(source)["workflow"]["features"]["file_upload"] == features["file_upload"]
path.write_text(source, encoding="utf-8")

# Global transport parameter uses Dify sys.conversation_id and is never inferred by the LLM.
api_path = ROOT / "docs/dify-agent-openapi.yaml"
api = api_path.read_text(encoding="utf-8")
if "    name: conversationId" not in api:
    api = re.sub(r'(^  /agent/[^\n]+:\n)', r'\1    parameters:\n      - in: query\n        name: conversationId\n        required: true\n        schema:\n          type: string\n          pattern: "^[A-Za-z0-9_-]{1,128}$"\n', api, flags=re.M)
# Dify 1.14's custom-tool parser reads operation parameters only, not path-level
# OpenAPI inheritance. Keep the transport variable visible to the actual runtime.
api = re.sub(r'(?m)^(  /agent/[^\n]+:\n)(    parameters:\n(?:      .*\n)+)(    (?:get|post|put|delete|patch):\n)',
             lambda m: m[1] + m[3] + ''.join('  ' + line for line in m[2].splitlines(True)), api)
for path_item in yaml.safe_load(api)["paths"].values():
    for method, operation in path_item.items():
        if method in ("get", "post", "put", "delete", "patch"):
            assert any(p.get("name") == "conversationId" for p in operation.get("parameters", []))
api_path.write_text(api, encoding="utf-8")

# Rebuild the native read loop after legacy repairs; retain the switchable fallback.
from repair_read_loop import rewrite as rewrite_read_loop
path.write_text(rewrite_read_loop(path.read_text(encoding="utf-8")), encoding="utf-8")
