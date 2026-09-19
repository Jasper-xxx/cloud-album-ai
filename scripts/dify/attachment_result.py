def main(raw, previous_file_id=""):
    import json
    result = {"message": "附件处理结果未知，请检查图库或重新查询；尚未确认保存成功。", "fileId": previous_file_id}
    try:
        value = json.loads(raw) if isinstance(raw, str) else raw
        if not isinstance(value, dict) or value.get("code") != 200:
            return result
        data = value.get("data")
        if isinstance(data, dict) and data.get("uploaded") is True and isinstance(data.get("fileId"), str) and data["fileId"]:
            result.update(message="附件已保存到图库。后续可以为这张照片生成标签建议或整理到相册。", fileId=data["fileId"])
        elif isinstance(data, list):
            names = [item.get("originFileName", "照片") for item in data[:30] if isinstance(item, dict)]
            result["message"] = "附件仅用于查询，没有保存到图库。找到 %s 个候选。" % len(data)
            if names:
                result["message"] += "\n" + "\n".join("- " + str(name).replace("\n", " ")[:200] for name in names)
    except (ValueError, TypeError):
        pass
    return result
