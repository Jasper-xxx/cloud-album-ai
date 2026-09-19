"""Targeted graph patch shared by the exported DSL and the existing local draft."""
from pathlib import Path


def enable_image_attachments(features):
    upload = features.setdefault("file_upload", {})
    upload.update(enabled=True, number_limits=1, allowed_file_types=["image"],
                  allowed_file_extensions=[".JPG", ".JPEG", ".PNG", ".GIF", ".WEBP"],
                  allowed_file_upload_methods=["local_file", "remote_url"])
    upload.setdefault("image", {}).update(enabled=True, number_limits=1,
                                           transfer_methods=["local_file", "remote_url"])


def apply(nodes):
    prompt = nodes["extract_write_action"]["data"]["prompt_template"][0]
    prompt["text"] = prompt["text"].replace(
        "High-risk actions must return mode=none: delete files, delete albums, empty recycle bin, create share links, create download tokens, update account.",
        "Supported P3/P4 actions below require mode=preview and server-issued confirmation. Account changes return mode=none.")
    prompt["text"] = prompt["text"].replace("none: no safe write action, high-risk request, or not enough parameters", "none: read-only request or unsupported action")
    marker = "Album photo deletion contract:"
    if marker not in prompt["text"]:
        prompt["text"] += '''
Album photo deletion contract:
- 删除/删掉照片 means move_files_to_recycle_bin (reversible), never permanently_delete_files or delete_albums. Only explicit 永久删除/彻底删除 requests can select permanently_delete_files.
- “帮我把相册 测试旅行 里面的图片删掉” => mode=preview, family=p4_action, extendedAction=move_files_to_recycle_bin, albumName=测试旅行, fileIds=[], albumIds=[], includePictures=false. The backend resolves the exact owned album and freezes its active images; the album itself remains.
- Use albumName only for all images in one explicitly named ordinary album. A filtered subset, videos, multiple/ambiguous albums or missing scope needs a clarification (mode=message). Never broaden these to all images. When fileIds are explicit leave albumName empty.
- Do not ask for confirmation, claim a preview exists or claim completion in reason. Only the real preview tool may ask for confirmation. Negated commands and questions about how deletion works must not generate a write preview.
'''
    parse = nodes["parse_write_action"]["data"]
    helper = Path(__file__).with_name("recycle_intent.py").read_text(encoding="utf-8")
    if "def guard_recycle_intent" in parse["code"]:
        parse["code"] = parse["code"][parse["code"].index("def main("):]
    if "data = guard_recycle_intent" not in parse["code"]:
        parse["code"] = parse["code"].replace('    exact_confirmations =', '    data = guard_recycle_intent(data, clean_text(current_query))\n    exact_confirmations =', 1)
    parse["code"] = helper + "\n\n" + parse["code"]
    if '"allRecycleImages": False' not in parse["code"]:
        parse["code"] = parse["code"].replace('"sourceAlbumId": -1, "confirmed": False', '"allRecycleImages": False, "sourceAlbumId": -1, "confirmed": False', 1)
    parse["outputs"]["allRecycleImages"] = {"type": "boolean", "children": None}
    nodes["tool_preview_p3_action"]["data"]["tool_parameters"]["allRecycleImages"] = {"type": "variable", "value": ["parse_write_action", "allRecycleImages"]}
    if "Recycle restoration contract:" not in prompt["text"]:
        prompt["text"] += '''
Recycle restoration contract:
- restore_files always uses family=p3_action, never p4_action. “把回收站的照片恢复” requests a real preview of current owned recycled images; the server resolves and freezes their IDs.
- Partial/filtered restore needs explicit known fileIds. Never invent IDs or silently broaden a subset. Empty recycle bin is a no-op, not a tool failure.
'''
    nodes["tool_preview_p4_action"]["data"]["tool_parameters"]["albumName"] = {"type": "variable", "value": ["parse_write_action", "albumName"]}
    read_prompt = nodes["extract_keyword"]["data"]["prompt_template"][0]
    if "只读分支没有生成待确认操作" not in read_prompt["text"]:
        read_prompt["text"] += "\n只读分支没有生成待确认操作。禁止要求用户确认写操作，禁止宣称已执行写入、删除或整理。写操作漏入时只说明尚未创建预览并澄清范围，普通删除指移入回收站。\n"
    read = nodes["validate_read_plan"]["data"]
    if "# No fabricated write confirmation" not in read["code"]:
        read["code"] = read["code"].replace('    return defaults', '''    # No fabricated write confirmation from a read-only model response.
    if re.search(r"确认|是否.*(?:删除|执行)|已(?:经)?(?:删除|移入|完成|执行|创建)", defaults["directReply"]):
        defaults["selectedTool"] = "none"
        defaults["directReply"] = "尚未创建新的待确认操作。写操作需要先取得实际预览；请明确要处理的照片范围。"
    return defaults''')
