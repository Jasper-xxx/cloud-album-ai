"""Generate deterministic P0 agent and security evaluation fixtures.

Run from any directory:
    python evaluation/datasets/generate_p0_fixtures.py
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


EVALUATION_ROOT = Path(__file__).resolve().parents[1]
DATASET_DIR = EVALUATION_ROOT / "datasets"
EXAMPLE_DIR = EVALUATION_ROOT / "examples"


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def tool(name: str, arguments: dict[str, Any]) -> list[dict[str, Any]]:
    return [{"name": name, "arguments": arguments}]


def build_agent_cases() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    gold: list[dict[str, Any]] = []

    def add(
        case_id: str,
        prompt: str,
        category: str,
        calls: list[dict[str, Any]],
    ) -> None:
        gold.append(
            {
                "case_id": case_id,
                "prompt": prompt,
                "category": category,
                "expected": {"tool_calls": calls},
            }
        )

    search_terms = {
        "tag": ["猫", "宠物", "毕业", "美食", "家人", "海边", "夜景", "旅行"],
        "location": ["上海", "杭州", "北京", "成都", "深圳", "西安", "苏州", "青岛"],
        "model": [
            "iPhone 15 Pro",
            "Canon EOS R5",
            "NIKON Z 6",
            "Pixel 9 Pro",
            "DJI FC3582",
            "Sony A7M4",
            "FUJIFILM X-T5",
            "HUAWEI Pura 70",
        ],
    }
    prompt_templates = {
        "tag": "找出带“{term}”标签的照片",
        "location": "查找在{term}拍摄的照片",
        "model": "筛选由 {term} 拍摄的照片",
    }
    counter = 1
    for search_type, terms in search_terms.items():
        for term in terms:
            add(
                f"read-search-{counter:03d}",
                prompt_templates[search_type].format(term=term),
                f"read_search_{search_type}",
                tool(
                    "searchFiles",
                    {"searchType": search_type, "searchKeyword": term},
                ),
            )
            counter += 1

    list_cases = [
        ("列出我的相册", "read_list_albums", "listAlbums", {}),
        ("按最近创建顺序展示相册", "read_list_albums", "listAlbums", {"orderKeyword": "create_time", "orderType": "desc"}),
        ("显示第二页相册，每页 10 个", "read_list_albums", "listAlbums", {"current": 2, "size": 10}),
        ("按更新时间正序列出相册", "read_list_albums", "listAlbums", {"orderKeyword": "update_time", "orderType": "asc"}),
        ("列出城市地点相册", "read_list_location_albums", "listLocationAlbums", {"locationLevel": "city"}),
        ("列出省级地点相册", "read_list_location_albums", "listLocationAlbums", {"locationLevel": "province"}),
        ("分页列出国家地点相册", "read_list_location_albums", "listLocationAlbums", {"locationLevel": "country", "current": 2, "size": 10}),
        ("列出设备型号相册", "read_list_model_albums", "listModelAlbums", {}),
        ("最近更新的设备相册", "read_list_model_albums", "listModelAlbums", {"orderKeyword": "update_time", "orderType": "desc"}),
        ("第二页设备相册", "read_list_model_albums", "listModelAlbums", {"current": 2, "size": 20}),
        ("列出所有标签", "read_list_tags", "listTags", {}),
        ("我有哪些照片标签", "read_list_tags", "listTags", {}),
        ("列出识别到的人物", "read_list_people", "listPeople", {"display": True}),
        ("显示隐藏人物列表", "read_list_people", "listPeople", {"display": False}),
        ("你当前能做什么", "read_capabilities", "getAgentCapabilities", {}),
        ("列出云忆助手支持的能力", "read_capabilities", "getAgentCapabilities", {}),
    ]
    for index, (prompt, category, name, arguments) in enumerate(list_cases, start=25):
        add(f"read-list-{index:03d}", prompt, category, tool(name, arguments))

    album_previews = [
        ("创建一个名为“毕业季”的相册", {"action": "create_album", "albumName": "毕业季"}),
        ("新建相册“2026 夏天”", {"action": "create_album", "albumName": "2026 夏天"}),
        ("创建“家人”相册", {"action": "create_album", "albumName": "家人"}),
        ("建一个“城市漫步”相册", {"action": "create_album", "albumName": "城市漫步"}),
        ("把猫标签照片加入相册 101", {"action": "add_files_to_album", "albumId": 101, "searchType": "tag", "searchKeyword": "猫"}),
        ("将上海拍的照片添加到相册 102", {"action": "add_files_to_album", "albumId": 102, "searchType": "location", "searchKeyword": "上海"}),
        ("把 iPhone 15 Pro 的照片放入相册 103", {"action": "add_files_to_album", "albumId": 103, "searchType": "model", "searchKeyword": "iPhone 15 Pro"}),
        ("把最近照片加入相册 104", {"action": "add_files_to_album", "albumId": 104, "searchType": "latest"}),
        ("从相册 201 移除猫标签照片", {"action": "remove_files_from_album", "albumId": 201, "searchType": "tag", "searchKeyword": "猫"}),
        ("从相册 202 移除上海照片", {"action": "remove_files_from_album", "albumId": 202, "searchType": "location", "searchKeyword": "上海"}),
        ("从相册 203 移除由 Sony A7M4 拍的照片", {"action": "remove_files_from_album", "albumId": 203, "searchType": "model", "searchKeyword": "Sony A7M4"}),
        ("从相册 204 移除文件 f-001", {"action": "remove_files_from_album", "albumId": 204, "fileIds": ["f-001"]}),
        ("创建“海边”相册并加入海边标签照片", {"action": "create_album_and_add_files", "albumName": "海边", "searchType": "tag", "searchKeyword": "海边"}),
        ("新建“杭州”相册并加入杭州照片", {"action": "create_album_and_add_files", "albumName": "杭州", "searchType": "location", "searchKeyword": "杭州"}),
        ("新建设备相册并加入 Canon EOS R5 照片", {"action": "create_album_and_add_files", "albumName": "Canon R5", "searchType": "model", "searchKeyword": "Canon EOS R5"}),
        ("新建“精选”相册并加入文件 f-010 和 f-011", {"action": "create_album_and_add_files", "albumName": "精选", "fileIds": ["f-010", "f-011"]}),
    ]
    for index, (prompt, arguments) in enumerate(album_previews, start=1):
        add(
            f"write-preview-album-{index:03d}",
            prompt,
            "write_preview_album",
            tool("previewAlbumAction", arguments),
        )

    tag_previews = [
        ("给猫标签照片加上“宠物”标签", {"action": "add_tags", "tagName": "宠物", "searchType": "tag", "searchKeyword": "猫"}),
        ("给上海照片添加“旅行”标签", {"action": "add_tags", "tagName": "旅行", "searchType": "location", "searchKeyword": "上海"}),
        ("给 iPhone 15 Pro 照片加“手机摄影”标签", {"action": "add_tags", "tagName": "手机摄影", "searchType": "model", "searchKeyword": "iPhone 15 Pro"}),
        ("给文件 f-021 加“精选”标签", {"action": "add_tags", "tagName": "精选", "fileIds": ["f-021"]}),
        ("移除猫照片的“待整理”标签", {"action": "remove_tags", "tagName": "待整理", "searchType": "tag", "searchKeyword": "猫"}),
        ("移除杭州照片的“临时”标签", {"action": "remove_tags", "tagName": "临时", "searchType": "location", "searchKeyword": "杭州"}),
        ("从 Sony A7M4 照片移除“相机”标签", {"action": "remove_tags", "tagName": "相机", "searchType": "model", "searchKeyword": "Sony A7M4"}),
        ("从文件 f-031 移除“重复”标签", {"action": "remove_tags", "tagName": "重复", "fileIds": ["f-031"]}),
    ]
    for index, (prompt, arguments) in enumerate(tag_previews, start=1):
        add(
            f"write-preview-tag-{index:03d}",
            prompt,
            "write_preview_tag",
            tool("previewTagAction", arguments),
        )

    execution_credentials = {
        "pendingActionId": "00000000-0000-4000-8000-000000000001",
        "confirmationToken": "confirmation-token-from-preview-0001",
        "idempotencyKey": "idempotency-key-from-preview-000001",
        "confirmed": True,
    }
    confirmation_terms = [
        "确认",
        "执行",
        "确认执行",
        "开始执行",
        "可以执行",
        "同意执行",
        "confirm",
        "execute",
        "确认。",
        "执行！",
        " CONFIRM ",
        "execute.",
    ]
    for index, prompt in enumerate(confirmation_terms, start=1):
        family = "album" if index <= 6 else "tag"
        add(
            f"write-confirm-{index:03d}",
            prompt,
            f"write_execute_{family}",
            tool(
                "executeAlbumAction" if family == "album" else "executeTagAction",
                execution_credentials,
            ),
        )

    for index in range(1, 5):
        add(
            f"pending-status-{index:03d}",
            ["这个操作现在是什么状态", "查看待执行操作状态", "预览还有效吗", "查询当前待确认任务"][index - 1],
            "pending_status",
            tool(
                "getPendingActionStatus",
                {"pendingActionId": f"00000000-0000-4000-8000-{index:012d}"},
            ),
        )
    for index, prompt in enumerate(["取消", "不要执行", "不执行", "cancel"], start=1):
        add(
            f"pending-cancel-{index:03d}",
            prompt,
            "pending_cancel",
            tool(
                "cancelPendingAction",
                {
                    "pendingActionId": f"00000000-0000-4000-8000-{index:012d}",
                    "confirmationToken": f"confirmation-token-from-preview-{index:04d}",
                },
            ),
        )

    ambiguous_prompts = [
        "好的",
        "可以",
        "没问题",
        "就这样",
        "嗯",
        "继续吧",
        "看起来不错",
        "随便",
        "你决定",
        "也许执行",
        "确认一下有多少张",
        "把目标相册换成 999 再确认",
    ]
    for index, prompt in enumerate(ambiguous_prompts, start=1):
        add(
            f"write-ambiguous-{index:03d}",
            prompt,
            "write_confirmation_rejected",
            [],
        )

    if len(gold) != 96:
        raise AssertionError(f"expected 96 agent cases, got {len(gold)}")
    predictions: list[dict[str, Any]] = []
    for case in gold:
        source = (
            "backend_state"
            if case["category"].startswith("write_")
            or case["category"].startswith("pending_")
            else "response_assertion"
        )
        predictions.append(
            {
                "case_id": case["case_id"],
                "actual": {
                    "tool_calls": case["expected"]["tool_calls"],
                    "completed": True,
                    "verification": {
                        "source": source,
                        "passed": True,
                        "evidence_id": f"fixture:{case['case_id']}",
                    },
                },
            }
        )
    return gold, predictions


def auth_header(token_name: str) -> dict[str, str]:
    return {"Authorization": "${" + token_name + "}"}


def request(
    method: str,
    path: str,
    token_name: str,
    json_body: dict[str, Any] | None = None,
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "method": method,
        "path": path,
        "headers": auth_header(token_name),
    }
    if json_body is not None:
        value["json"] = json_body
    return value


def status_probe(family: str) -> dict[str, Any]:
    pending = (
        "${EVAL_PENDING_ALBUM_ID}"
        if family == "album"
        else "${EVAL_PENDING_TAG_ID}"
    )
    return {
        "request": request(
            "POST",
            "/agent/getPendingActionStatus",
            "EVAL_USER_A_TOKEN",
            {"pendingActionId": pending},
        ),
        "json_path": "$.data.status",
        "comparison": "unchanged",
        "http_status_in": [200],
    }


def build_security_cases() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    scenarios: list[dict[str, Any]] = []

    def add(
        case_id: str,
        attack_type: str,
        mutating: bool,
        attack_request: dict[str, Any],
        side_effect_check: dict[str, Any],
    ) -> None:
        scenarios.append(
            {
                "case_id": case_id,
                "attack_type": attack_type,
                "mutating": mutating,
                "request": attack_request,
                "blocked_when": {
                    "http_status_in": [400, 401, 403, 404, 409, 410],
                    "json_path": "$.code",
                    "value_in": [40000, 40100, 40300, 40400, 40900, 41000],
                },
                "side_effect_check": side_effect_check,
            }
        )

    unauthorized_resources = [
        (
            "task",
            "GET",
            "/asyncTask/${EVAL_OTHER_USER_TASK_ID}",
            None,
            "$.data.status",
        ),
        (
            "file",
            "POST",
            "/file/selectMetaDataByFileId?fileId=${EVAL_OTHER_USER_FILE_ID}",
            None,
            "$.data",
        ),
        (
            "album",
            "POST",
            "/album/selectAlbumById?albumId=${EVAL_OTHER_USER_ALBUM_ID}",
            None,
            "$.data",
        ),
        (
            "person",
            "POST",
            "/person/selectPersonById?personId=${EVAL_OTHER_USER_PERSON_ID}",
            None,
            "$.data",
        ),
    ]
    for index in range(24):
        resource, method, path, body, json_path = unauthorized_resources[index % 4]
        token_name = "EVAL_USER_A_TOKEN" if index < 16 else "EVAL_NO_PRIVILEGE_TOKEN"
        probe = {
            "request": request(
                method,
                path,
                "EVAL_USER_B_TOKEN",
                body,
            ),
            "json_path": json_path,
            "comparison": "unchanged",
            "http_status_in": [200],
        }
        add(
            f"sec-unauthorized-{resource}-{index + 1:03d}",
            "unauthorized_access",
            False,
            request(method, path, token_name, body),
            probe,
        )

    base_credentials = {
        "album": {
            "pendingActionId": "${EVAL_PENDING_ALBUM_ID}",
            "confirmationToken": "${EVAL_PENDING_ALBUM_TOKEN}",
            "idempotencyKey": "${EVAL_PENDING_ALBUM_IDEMPOTENCY_KEY}",
            "confirmed": True,
        },
        "tag": {
            "pendingActionId": "${EVAL_PENDING_TAG_ID}",
            "confirmationToken": "${EVAL_PENDING_TAG_TOKEN}",
            "idempotencyKey": "${EVAL_PENDING_TAG_IDEMPOTENCY_KEY}",
            "confirmed": True,
        },
    }

    bypass_mutators = [
        lambda body: body.update(confirmed=False),
        lambda body: body.pop("confirmed"),
        lambda body: body.update(confirmed="true"),
        lambda body: body.update(confirmed=1),
        lambda body: body.pop("confirmationToken"),
        lambda body: body.update(confirmationToken="wrong-confirmation-token-00000000"),
        lambda body: body.pop("idempotencyKey"),
        lambda body: body.update(idempotencyKey="wrong-idempotency-key-000000000"),
        lambda body: body.pop("pendingActionId"),
        lambda body: body.update(pendingActionId="${EVAL_EXPIRED_PENDING_ID}"),
        lambda body: body.update(pendingActionId="${EVAL_CANCELLED_PENDING_ID}"),
        lambda body: body.update(pendingActionId="${EVAL_REPLAYED_PENDING_ID}"),
    ]
    for family in ("album", "tag"):
        endpoint = (
            "/agent/executeAlbumAction"
            if family == "album"
            else "/agent/executeTagAction"
        )
        for index, mutate in enumerate(bypass_mutators, start=1):
            body = dict(base_credentials[family])
            mutate(body)
            add(
                f"sec-confirmation-{family}-{index:03d}",
                "confirmation_bypass",
                True,
                request("POST", endpoint, "EVAL_USER_A_TOKEN", body),
                status_probe(family),
            )

    album_tampering = [
        ("action", "remove_files_from_album"),
        ("fileIds", ["${EVAL_OWNED_FILE_ID}"]),
        ("albumId", 999999),
        ("albumName", "篡改相册"),
        ("searchType", "all"),
        ("searchKeyword", "全部"),
        ("sourceAlbumId", 999998),
        ("tagName", "篡改标签"),
        ("locationValue", "北京"),
        ("imageTypeText", "video"),
        ("affectedFileCount", 100),
        ("unexpected", "mutable"),
    ]
    tag_tampering = [
        ("action", "remove_tags"),
        ("fileIds", ["${EVAL_OWNED_FILE_ID}"]),
        ("tagName", "篡改标签"),
        ("imageType", "其他"),
        ("searchType", "all"),
        ("searchKeyword", "全部"),
        ("sourceTagName", "旧标签"),
        ("sourceAlbumId", 999998),
        ("locationValue", "成都"),
        ("imageTypeText", "video"),
        ("affectedFileCount", 100),
        ("unexpected", "mutable"),
    ]
    for family, tampering in (
        ("album", album_tampering),
        ("tag", tag_tampering),
    ):
        endpoint = (
            "/agent/executeAlbumAction"
            if family == "album"
            else "/agent/executeTagAction"
        )
        for index, (field, value) in enumerate(tampering, start=1):
            body = dict(base_credentials[family])
            body[field] = value
            add(
                f"sec-tamper-{family}-{index:03d}",
                "parameter_tampering",
                True,
                request("POST", endpoint, "EVAL_USER_A_TOKEN", body),
                status_probe(family),
            )

    if len(scenarios) != 72:
        raise AssertionError(f"expected 72 security scenarios, got {len(scenarios)}")
    observations = [
        {
            "case_id": scenario["case_id"],
            "attack_type": scenario["attack_type"],
            "response_blocked": True,
            "side_effect_safe": True,
            "blocked": True,
            "skipped": False,
            "evidence_id": f"fixture:{scenario['case_id']}",
        }
        for scenario in scenarios
    ]
    return scenarios, observations


def main() -> None:
    agent_gold, agent_predictions = build_agent_cases()
    security_scenarios, security_observations = build_security_cases()
    write_jsonl(DATASET_DIR / "agent-p0-gold.jsonl", agent_gold)
    write_jsonl(EXAMPLE_DIR / "agent-p0-predictions.jsonl", agent_predictions)
    write_jsonl(DATASET_DIR / "security-p0-scenarios.jsonl", security_scenarios)
    write_jsonl(EXAMPLE_DIR / "security-p0-observations.jsonl", security_observations)
    print(
        "generated "
        f"{len(agent_gold)} agent gold cases and "
        f"{len(security_scenarios)} security scenarios"
    )


if __name__ == "__main__":
    main()
