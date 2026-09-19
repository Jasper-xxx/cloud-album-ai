"""Run inside the deployed Dify API container against its actual node schemas."""
import json
import yaml
from pathlib import Path
from uuid import UUID
from graphon.nodes.tool.entities import ToolNodeData
from graphon.nodes.code.entities import CodeNodeData
from graphon.nodes.if_else.entities import IfElseNodeData
from graphon.nodes.list_operator.entities import ListOperatorNodeData

root = yaml.safe_load(Path("/tmp/cloud-album-repair.yml").read_text())
for variable in root["workflow"].get("conversation_variables", []):
    # Accepted as a string by node schemas, but persisted to a PostgreSQL UUID column.
    UUID(variable["id"])
schemas = {"tool": ToolNodeData, "code": CodeNodeData, "if-else": IfElseNodeData, "list-operator": ListOperatorNodeData}
checked = 0
for node in root["workflow"]["graph"]["nodes"]:
    data = node["data"]
    if data["type"] in schemas:
        try:
            schemas[data["type"]].model_validate(data)
        except Exception as error:
            raise RuntimeError("Invalid runtime schema for " + node["id"]) from error
        checked += 1
print(json.dumps({"validatedRuntimeNodes": checked, "schemas": list(schemas)}))
