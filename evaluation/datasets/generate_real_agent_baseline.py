from __future__ import annotations

import argparse
import json
from pathlib import Path


def case(case_id: str, prompt: str, category: str, name: str, arguments: dict) -> dict:
    return {
        "case_id": case_id,
        "prompt": prompt,
        "category": category,
        "expected": {"tool_calls": [{"name": name, "arguments": arguments}]},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--album-a", type=int, required=True)
    parser.add_argument("--album-b", type=int, required=True)
    parser.add_argument("--file-a", required=True)
    parser.add_argument("--file-b", required=True)
    args = parser.parse_args()

    source = [
        json.loads(line)
        for line in args.source.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    records = []
    for row in source:
        if not row["category"].startswith("read_"):
            continue
        normalized = json.loads(json.dumps(row, ensure_ascii=False))
        if row["category"] == "read_search_location":
            keyword = row["expected"]["tool_calls"][0]["arguments"]["searchKeyword"]
            normalized["expected"]["tool_calls"] = [
                {"name": "advancedSearchFiles", "arguments": {"city": keyword}}
            ]
        elif row["category"] == "read_search_model":
            keyword = row["expected"]["tool_calls"][0]["arguments"]["searchKeyword"]
            normalized["expected"]["tool_calls"] = [
                {"name": "advancedSearchFiles", "arguments": {"model": keyword}}
            ]
        records.append(normalized)

    album_cases = [
        ("real-album-001", "创建一个名为“Agent评测毕业季”的相册", "create_album", {"albumName": "Agent评测毕业季"}),
        ("real-album-002", "新建相册“Agent评测夏天”", "create_album", {"albumName": "Agent评测夏天"}),
        ("real-album-003", "创建“Agent评测家人”相册", "create_album", {"albumName": "Agent评测家人"}),
        ("real-album-004", "建一个“Agent评测城市漫步”相册", "create_album", {"albumName": "Agent评测城市漫步"}),
        ("real-album-005", f"把文件 {args.file_b} 加入相册 {args.album_a}", "add_files_to_album", {"albumId": args.album_a, "fileIds": [args.file_b]}),
        ("real-album-006", f"将文件 {args.file_a} 添加到相册 {args.album_b}", "add_files_to_album", {"albumId": args.album_b, "fileIds": [args.file_a]}),
        ("real-album-007", f"把最近 1 张照片放入相册 {args.album_a}", "add_files_to_album", {"albumId": args.album_a, "searchType": "latest", "selectionLimit": 1}),
        ("real-album-008", f"把小猫标签照片加入相册 {args.album_b}", "add_files_to_album", {"albumId": args.album_b, "searchType": "tag", "searchKeyword": "小猫"}),
        ("real-album-009", f"从相册 {args.album_a} 移除文件 {args.file_a}", "remove_files_from_album", {"albumId": args.album_a, "fileIds": [args.file_a]}),
        ("real-album-010", f"请预览从相册 {args.album_a} 移除文件 {args.file_a}", "remove_files_from_album", {"albumId": args.album_a, "fileIds": [args.file_a]}),
        ("real-album-011", f"准备把文件 {args.file_a} 从相册 {args.album_a} 移出去", "remove_files_from_album", {"albumId": args.album_a, "fileIds": [args.file_a]}),
        ("real-album-012", f"将相册 {args.album_a} 中的文件 {args.file_a} 移除", "remove_files_from_album", {"albumId": args.album_a, "fileIds": [args.file_a]}),
        ("real-album-013", "创建“Agent评测小猫”相册并加入小猫标签照片", "create_album_and_add_files", {"albumName": "Agent评测小猫", "searchType": "tag", "searchKeyword": "小猫"}),
        ("real-album-014", f"新建“Agent评测精选A”相册并加入文件 {args.file_a}", "create_album_and_add_files", {"albumName": "Agent评测精选A", "fileIds": [args.file_a]}),
        ("real-album-015", f"新建“Agent评测精选B”相册并加入文件 {args.file_b}", "create_album_and_add_files", {"albumName": "Agent评测精选B", "fileIds": [args.file_b]}),
        ("real-album-016", "新建“Agent评测最近”相册并加入最近 1 张照片", "create_album_and_add_files", {"albumName": "Agent评测最近", "searchType": "latest", "selectionLimit": 1}),
    ]
    for case_id, prompt, action, arguments in album_cases:
        records.append(
            case(
                case_id,
                prompt,
                "write_preview_album",
                "previewAlbumAction",
                {"action": action, **arguments},
            )
        )

    tag_cases = [
        ("real-tag-001", f"给文件 {args.file_a} 添加“Agent评测精选”标签", "add_tags", {"tagName": "Agent评测精选", "fileIds": [args.file_a]}),
        ("real-tag-002", f"给文件 {args.file_b} 添加“Agent评测归档”标签", "add_tags", {"tagName": "Agent评测归档", "fileIds": [args.file_b]}),
        ("real-tag-003", "给最近 1 张照片加上“Agent评测最近”标签", "add_tags", {"tagName": "Agent评测最近", "searchType": "latest", "selectionLimit": 1}),
        ("real-tag-004", "给小猫标签照片添加“Agent评测宠物”标签", "add_tags", {"tagName": "Agent评测宠物", "searchType": "tag", "searchKeyword": "小猫"}),
        ("real-tag-005", "移除小猫照片的“小猫”标签", "remove_tags", {"tagName": "小猫", "searchType": "tag", "searchKeyword": "小猫"}),
        ("real-tag-006", "从胡歌标签照片移除“胡歌”标签", "remove_tags", {"tagName": "胡歌", "searchType": "tag", "searchKeyword": "胡歌"}),
        ("real-tag-007", "移除仓鼠照片上的“仓鼠”标签", "remove_tags", {"tagName": "仓鼠", "searchType": "tag", "searchKeyword": "仓鼠"}),
        ("real-tag-008", "从日出标签照片去掉“日出”标签", "remove_tags", {"tagName": "日出", "searchType": "tag", "searchKeyword": "日出"}),
    ]
    for case_id, prompt, action, arguments in tag_cases:
        records.append(
            case(
                case_id,
                prompt,
                "write_preview_tag",
                "previewTagAction",
                {"action": action, **arguments},
            )
        )

    records.extend(row for row in source if row["category"] == "write_confirmation_rejected")

    paraphrases = [
        case("real-read-001", "看看我都建了哪些相册", "read_paraphrase", "listAlbums", {}),
        case("real-read-002", "把普通相册清单给我", "read_paraphrase", "listAlbums", {}),
        case("real-read-003", "相册按创建时间从新到旧排列", "read_paraphrase", "listAlbums", {"orderKeyword": "create_time", "orderType": "desc"}),
        case("real-read-004", "每页十个，查看第二页相册", "read_paraphrase", "listAlbums", {"current": 2, "size": 10}),
        case("real-read-005", "有哪些带小猫标签的图", "read_paraphrase", "searchFiles", {"searchType": "tag", "searchKeyword": "小猫"}),
        case("real-read-006", "帮我找胡歌标签的照片", "read_paraphrase", "searchFiles", {"searchType": "tag", "searchKeyword": "胡歌"}),
        case("real-read-007", "搜索仓鼠标签图片", "read_paraphrase", "searchFiles", {"searchType": "tag", "searchKeyword": "仓鼠"}),
        case("real-read-008", "找一下日出标签的照片", "read_paraphrase", "searchFiles", {"searchType": "tag", "searchKeyword": "日出"}),
        case("real-read-009", "我都有哪些标签", "read_paraphrase", "listTags", {}),
        case("real-read-010", "展示标签列表", "read_paraphrase", "listTags", {}),
        case("real-read-011", "看看识别出哪些人物", "read_paraphrase", "listPeople", {"display": True}),
        case("real-read-012", "把被隐藏的人物也列出来", "read_paraphrase", "listPeople", {"display": False}),
        case("real-read-013", "按城市看看地点相册", "read_paraphrase", "listLocationAlbums", {"locationLevel": "city"}),
        case("real-read-014", "展示设备型号相册", "read_paraphrase", "listModelAlbums", {}),
        case("real-read-015", "这个助手支持哪些操作", "read_paraphrase", "getAgentCapabilities", {}),
        case("real-read-016", "介绍一下你能调用的相册能力", "read_paraphrase", "getAgentCapabilities", {}),
    ]
    records.extend(paraphrases)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in records),
        encoding="utf-8",
    )
    print(f"generated {len(records)} cases: {args.output}")


if __name__ == "__main__":
    main()
