def main(raw, current_task_id=""):
    import json
    from urllib.parse import urlsplit

    message = "暂时无法确认执行结果，正在查询服务端状态；请勿重复发起操作。"
    result = {"publicMessage": message, "message": message, "terminal": False,
              "agentTaskId": current_task_id if isinstance(current_task_id, str) else "", "empty": ""}
    try:
        envelope = json.loads(raw) if isinstance(raw, str) else raw
        if not isinstance(envelope, dict) or envelope.get("code") != 200:
            return result
        data = envelope.get("data")
        if not isinstance(data, dict) or data.get("success") is not True:
            return result
        for key in ("affectedFileCount", "skippedFileCount", "createdAlbumCount"):
            value = data.get(key, 0)
            if type(value) is not int or value < 0:
                return result
        message = data.get("message")
        if not isinstance(message, str) or not message.strip():
            message = "操作已完成。"
        message += " 实际处理 %s 个文件，跳过 %s 个。" % (data.get("affectedFileCount", 0), data.get("skippedFileCount", 0))
        names = []
        files = data.get("affectedFiles")
        if isinstance(files, list):
            for item in files[:50]:
                if isinstance(item, dict) and isinstance(item.get("originFileName"), str):
                    name = item["originFileName"].replace("\n", " ").replace("\r", " ")[:200]
                    for symbol in "\\`*_{}[]<>()!#":
                        name = name.replace(symbol, "\\" + symbol)
                    names.append("- " + name)
        if names:
            message += "\n\n实际处理的照片：\n" + "\n".join(names)
        url = data.get("resourceUrl")
        if isinstance(url, str):
            parsed = urlsplit(url)
            if parsed.scheme in ("http", "https") and parsed.hostname and not parsed.username and not any(c in url for c in "\r\n<>\\\" "):
                message += "\n\n[打开分享或下载](" + url.replace("(", "%28").replace(")", "%29") + ")"
        task_id = data.get("agentTaskId")
        if isinstance(task_id, str) and task_id:
            result["agentTaskId"] = task_id
            message += " 可回复「AI任务状态」查询进度。"
        result.update(publicMessage=message, message=message, terminal=True)
    except (ValueError, TypeError, AttributeError):
        pass
    return result
