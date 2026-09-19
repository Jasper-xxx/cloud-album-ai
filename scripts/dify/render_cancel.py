def main(raw):
    import json
    message = "暂时无法确认取消结果，正在查询服务端状态。"
    result = {"publicMessage": message, "message": message, "terminal": False, "agentTaskId": "", "empty": ""}
    try:
        envelope = json.loads(raw) if isinstance(raw, str) else raw
        data = envelope.get("data") if isinstance(envelope, dict) and envelope.get("code") == 200 else None
        if isinstance(data, dict) and data.get("status") == "CANCELLED":
            result.update(publicMessage="待执行操作已取消。", message="待执行操作已取消。", terminal=True)
    except (ValueError, TypeError):
        pass
    return result
