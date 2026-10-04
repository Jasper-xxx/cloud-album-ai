"""File-only inspection, separate from policy tests and online verification.

Parse YAML/AST and compare exported files; never execute Code-node main(),
start services, query data, or invoke a model. Can be run before manual import.
"""
import ast
import builtins
from copy import deepcopy
import json
import hashlib
from pathlib import Path
import subprocess
import symtable
import yaml

ROOT = Path(__file__).resolve().parents[2]
BASELINE_COMMIT = "4ccef7850fb77570cf5b601a41816abf1937a736"
path = ROOT / "docs/云忆相册助手-write.yml"
source = path.read_text(encoding="utf-8")
workflow = yaml.safe_load(source)
graph = workflow["workflow"]["graph"]
nodes = {node["id"]: node for node in graph["nodes"]}
issues = []
if len(nodes) != len(graph["nodes"]):
    issues.append("duplicate node IDs")
if len({edge["id"] for edge in graph["edges"]}) != len(graph["edges"]):
    issues.append("duplicate edge IDs")
code_count = 0
for node in nodes.values():
    data = node["data"]
    if data["type"] == "llm":
        memory = data.get("memory")
        # Match local 1.14.2 nodes/llm/default.ts publish-check semantics.
        if memory is not None and data.get("model", {}).get("mode") == "chat":
            query_template = memory.get("query_prompt_template", "")
            if query_template and "{{#sys.query#}}" not in query_template:
                issues.append(node["id"] + ": Dify memory user template requires sys.query")
        if node["id"] in {"read_loop_planner_first", "read_loop_planner_next"} and memory is not None:
            issues.append(node["id"] + ": loop planner must not enable conversation memory")
    if data["type"] != "code":
        continue
    tree = ast.parse(data["code"])
    main = next((item for item in tree.body if isinstance(item, ast.FunctionDef) and item.name == "main"), None)
    declared = {v["variable"] for v in data["variables"]}
    accepted = {arg.arg for arg in main.args.args} if main else set()
    required = {arg.arg for arg in main.args.args[:len(main.args.args) - len(main.args.defaults)]} if main else set()
    if main is None or not required <= declared <= accepted:
        issues.append(node["id"] + ": main/input mismatch")
    if node["id"].startswith("read_loop_"):
        code_count += 1
        symbols = symtable.symtable(data["code"], node["id"], "exec")
        defined = {symbol.get_name() for symbol in symbols.get_symbols() if symbol.is_assigned() or symbol.is_imported() or symbol.is_namespace()}
        known = defined | set(dir(builtins))
        def inspect(table):
            for symbol in table.get_symbols():
                if symbol.is_referenced() and symbol.is_global() and symbol.get_name() not in known:
                    issues.append(node["id"] + ": unresolved global " + symbol.get_name())
            for child in table.get_children():
                inspect(child)
        inspect(symbols)
        if "from read_loop_" in data["code"] or "import scripts.dify" in data["code"]:
            issues.append(node["id"] + ": repository import in sandbox")

children = {n["id"] for n in nodes.values() if n.get("parentId") == "read_loop"}
for edge in graph["edges"]:
    if edge["source"] not in nodes or edge["target"] not in nodes:
        issues.append(edge["id"] + ": unresolved edge")
    if edge["source"] in children and (edge["target"] not in children or not edge["data"].get("isInLoop")):
        issues.append(edge["id"] + ": loop child escapes")

def selectors(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {"value_selector", "variable_selector", "variable"} and isinstance(item, list) and all(isinstance(x, str) for x in item) and len(item) >= 2:
                yield item
            if key in {"value", "variables"}:
                if key == "value" and "variable" in {value.get("type"), value.get("input_type"), value.get("value_type")} and isinstance(item, list):
                    yield item
                elif key == "variables" and isinstance(item, list):
                    for row in item:
                        if isinstance(row, list) and len(row) >= 2 and all(isinstance(x, str) for x in row):
                            yield row
            yield from selectors(item)
    elif isinstance(value, list):
        for item in value:
            yield from selectors(item)

for node in nodes.values():
    for selector in selectors(node["data"]):
        owner = selector[0]
        if owner not in {"sys", "env", "conversation"} and owner not in nodes:
            issues.append(node["id"] + ": unresolved selector " + repr(selector))
        elif owner in nodes:
            data = nodes[owner]["data"]
            if data["type"] == "code" and selector[1] not in data["outputs"]:
                issues.append(node["id"] + ": unresolved code output " + repr(selector))
            if data["type"] == "loop" and selector[1] not in {v["label"] for v in data["loop_variables"]}:
                issues.append(node["id"] + ": unresolved loop variable " + repr(selector))

# Pin the implementation baseline so committing delivery does not weaken or
# invalidate the exact protected-node comparison.
baseline = subprocess.run(["git", "show", BASELINE_COMMIT + ":docs/云忆相册助手-write.yml"], cwd=ROOT,
                          capture_output=True, check=True).stdout.decode("utf-8")
old = yaml.safe_load(baseline)["workflow"]["graph"]
changed_original_nodes = [n["id"] for n in old["nodes"] if n != nodes.get(n["id"])]
from read_continuation import apply_read_continuation_policy
expected_originals = {n["id"]: deepcopy(n) for n in old["nodes"]}
apply_read_continuation_policy(expected_originals)
def expected_preview_selectors(expected):
    # Two exact receiver changes: merge mutually exclusive original/bridge
    # Preview text, and use the endpoint's known family. Code/save nodes stay.
    for family in ("album", "tag"):
        for variable in expected["capture_pending_" + family]["data"]["variables"]:
            if variable["variable"] in {"raw", "expected_family"}:
                variable["value_selector"] = ["read_loop_preview_" + family + "_family", "raw" if variable["variable"] == "raw" else "family"]
    # Focused runtime regression found compound/question attachment saves were
    # accepted. Permit only the exact current deterministic intent source.
    expected["attachment_intent"]["data"]["code"] = (ROOT / "scripts/dify/attachment_intent.py").read_text(encoding="utf-8")
expected_preview_selectors(expected_originals)
unexpected_original_nodes = [identifier for identifier, node in expected_originals.items()
                             if node != nodes.get(identifier)]
if unexpected_original_nodes:
    issues.append("unexpected original node changes: " + repr(unexpected_original_nodes))
old_edges = {e["id"]: e for e in old["edges"]}
current_edges = {e["id"]: e for e in graph["edges"]}
changed_edges = [key for key, edge in old_edges.items() if edge != current_edges.get(key)]
expected_edges = deepcopy(old_edges)
edge_targets = {("start", "source"): ("read_loop_config", "code"),
                ("route_write_message", "false"): ("read_loop_gate", "if-else"),
                ("parse_write_action", "source"): ("read_loop_scope_entry", "code"),
                ("tool_preview_album_action", "source"): ("read_loop_preview_album_result", "variable-aggregator"),
                ("tool_preview_tag_action", "source"): ("read_loop_preview_tag_result", "variable-aggregator")}
for edge in expected_edges.values():
    replacement = edge_targets.get((edge["source"], edge["sourceHandle"]))
    if replacement:
        edge["target"], edge["data"]["targetType"] = replacement
unexpected_edges = [key for key, edge in expected_edges.items() if edge != current_edges.get(key)]
if unexpected_edges:
    issues.append("unexpected original edge content: " + repr(unexpected_edges))
snapshot = ROOT / "docs/releases/dify-read-loop-phase1-2026-10-04.yml"
# Release archives are local-only. Git normalized this Windows archive to LF;
# reconstruct its frozen CRLF bytes to retain the original hash/contract checks.
snapshot_bytes = snapshot.read_bytes() if snapshot.exists() else subprocess.run(
    ["git", "show", "7bc3cc41f165b03342191cf887fa86c72b3fee69:docs/releases/dify-read-loop-phase1-2026-10-04.yml"],
    cwd=ROOT, capture_output=True, check=True,
).stdout.replace(b"\n", b"\r\n")
if hashlib.sha256(snapshot_bytes).hexdigest() != "269227f5a45cbbf06a0aab75a0998a11ae7444255cb289f5578c5452a205536c":
    issues.append("phase1 frozen snapshot changed")
phase1 = yaml.safe_load(snapshot_bytes.decode("utf-8"))
phase1_originals = {n["id"]: deepcopy(n) for n in phase1["workflow"]["graph"]["nodes"] if n["id"] in expected_originals}
apply_read_continuation_policy(phase1_originals)
expected_preview_selectors(phase1_originals)
if any(node != nodes.get(key) for key, node in phase1_originals.items()):
    issues.append("phase1 original node changed beyond the exact receiver/attachment/pending-guide repairs")
for key in ("conversation_variables", "features", "environment_variables"):
    if workflow["workflow"].get(key) != phase1["workflow"].get(key):
        issues.append("phase1 workflow settings changed: " + key)
allowed = {"getAgentCapabilities", "searchFiles", "advancedSearchFiles", "listAlbums", "listLocationAlbums", "listModelAlbums", "listTags", "listPeople", "analyzeLibrary"}
for identifier in children:
    data = nodes[identifier]["data"]
    if data["type"] == "tool" and data.get("tool_name") not in allowed:
        issues.append(identifier + ": non-read tool inside Loop")
    for selector in selectors(data):
        if selector[0] == "conversation":
            issues.append(identifier + ": conversation state enters Loop")
for family in ("album", "tag"):
    node = nodes["read_loop_preview_" + family]
    data = node["data"]
    if node.get("parentId") or data["tool_name"] != "preview" + family.title() + "Action" or data["retry_config"]["retry_enabled"]:
        issues.append(node["id"] + ": Preview location/tool/retry")
    if data["tool_parameters"].get("fileIds") != {"type": "variable", "value": ["read_loop_preview_request", "fileIds"]}:
        issues.append(node["id"] + ": file IDs must come from code evidence")
    if "confirmed" in data["tool_parameters"]:
        issues.append(node["id"] + ": Preview must not confirm")
    if data["tool_parameters"].get("conversationId") != {"type": "variable", "value": ["sys", "conversation_id"]}:
        issues.append(node["id"] + ": native conversation binding")
# Every descendant after Loop may finish or preview, never execute/cancel/read
# confirmation credentials through a model. This checks complete reachability.
reachable, queue = set(), ["read_loop"]
while queue:
    identifier = queue.pop()
    if identifier in reachable:
        continue
    reachable.add(identifier)
    queue.extend(e["target"] for e in graph["edges"] if e["source"] == identifier)
for identifier in reachable:
    data = nodes[identifier]["data"]
    if data["type"] == "tool" and data.get("tool_name") not in {"previewAlbumAction", "previewTagAction"}:
        issues.append(identifier + ": disallowed post-Loop tool")
    if data["type"] == "llm":
        issues.append(identifier + ": unexpected model after Loop")
# The only production Java change is the explicit-ID tag-selector guard. Keep
# owner, conversation, confirmation and idempotency code byte-for-byte intact.
controller_path = "backend/src/main/java/com/memory/xzp/controller/AgentController.java"
controller_before = subprocess.run(["git", "show", BASELINE_COMMIT + ":" + controller_path], cwd=ROOT,
                                   capture_output=True, check=True).stdout.decode("utf-8")
controller_now = (ROOT / controller_path).read_text(encoding="utf-8")
needle = '    private List<String> resolveTagActionFileIds(AgentTagActionRequest request, Long userId) {\n        List<String> explicitFileIds = normalizeOwnedFileIds(request.getFileIds(), userId);\n        if (!explicitFileIds.isEmpty()) {'
replacement = '    private List<String> resolveTagActionFileIds(AgentTagActionRequest request, Long userId) {\n        List<String> explicitFileIds = normalizeOwnedFileIds(request.getFileIds(), userId);\n        // An explicitly frozen selection must never fall back to a tag-wide\n        // selector if ownership/deletion changed after the preceding query.\n        if (request.getFileIds() != null && !request.getFileIds().isEmpty()) {'
if controller_before.count(needle) != 1 or controller_before.replace(needle, replacement, 1) != controller_now:
    issues.append("production controller changed beyond the explicit-ID tag-selector guard")
from repair_read_loop import rewrite
if rewrite(source) != source:
    issues.append("generator is not byte-idempotent")
for policy in ("read_continuation.py", "read_loop_state.py", "read_loop_validate.py", "read_loop_observation.py", "read_write_bridge.py", "repair_read_loop.py"):
    ast.parse((ROOT / "scripts/dify" / policy).read_text(encoding="utf-8"))
for test in (ROOT / "evaluation/tests").glob("test_read_loop*.py"):
    ast.parse(test.read_text(encoding="utf-8"))
ast.parse((ROOT / "evaluation/tests/test_read_write_bridge.py").read_text(encoding="utf-8"))
ast.parse((ROOT / "evaluation/tests/test_dify_python_nodes.py").read_text(encoding="utf-8"))
print(json.dumps({"inspection": "file-only YAML/AST/references/diff/idempotence",
                  "nodes": len(nodes), "loopChildren": len(children), "embeddedCodeNodes": code_count,
                  "changedOriginalNodes": changed_original_nodes,
                  "unexpectedOriginalNodes": unexpected_original_nodes,
                  "changedOriginalEdges": changed_edges, "unexpectedOriginalEdges": unexpected_edges,
                  "phase1SnapshotPreserved": True, "postLoopReachable": sorted(reachable), "issues": sorted(set(issues)),
                  "testsExecuted": False, "servicesStarted": False}, ensure_ascii=True))
raise SystemExit(1 if issues else 0)
