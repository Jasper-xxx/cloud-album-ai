def main(raw, pending_action_id="", pending_confirmation_token="", pending_idempotency_key="", pending_family="", pending_expires_at="", expected_family=""):
    import json
    import re

    message = "预览结果不可用，尚未确认新的操作范围。请重试预览或查询原操作状态。"
    result = {"pendingActionId": pending_action_id, "confirmationToken": pending_confirmation_token,
              "idempotencyKey": pending_idempotency_key, "family": pending_family, "expiresAt": pending_expires_at,
              "message": message, "publicMessage": message}
    try:
        envelope = json.loads(raw) if isinstance(raw, str) else raw
        if not isinstance(envelope, dict) or envelope.get("code") != 200:
            safe_errors = {
                "未找到该名称的普通相册，请核对相册名称",
                "存在同名相册，请先查询并明确要处理的照片范围",
                "该相册没有可移入回收站的图片，未创建待确认操作",
                "该相册图片超过20张，请明确分批照片范围；未创建待确认操作",
                "请只指定一个相册名称，不要混用文件或相册 ID 范围",
            }
            error = envelope.get("message") if isinstance(envelope, dict) else None
            if isinstance(error, str) and error in safe_errors:
                message = error + "。本次未生成新的待确认操作。"
                if pending_action_id:
                    message += "原待确认操作仍保留；请先查询状态或取消原操作。"
                result.update(message=message, publicMessage=message)
            return result
        data = envelope.get("data")
        if not isinstance(data, dict) or type(data.get("requiresConfirmation")) is not bool:
            return result
        if data["requiresConfirmation"]:
            identifier = data.get("pendingActionId")
            token = data.get("confirmationToken")
            key = data.get("idempotencyKey")
            expiry = data.get("expiresAt")
            if not isinstance(identifier, str) or not re.fullmatch(r"[0-9a-fA-F-]{36}", identifier):
                return result
            if any(not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", value) for value in (token, key)):
                return result
            if not isinstance(expiry, str) or expected_family not in {"album", "tag", "ai_task", "suggested_tag", "p3_action", "p4_action"}:
                return result
            result.update(pendingActionId=identifier, confirmationToken=token, idempotencyKey=key, family=expected_family, expiresAt=expiry)
            message = data.get("confirmationPrompt") or data.get("summary") or "预览已生成，请确认是否执行。"
        else:
            message = data.get("summary") or "没有需要执行的变更，原待确认操作仍保留。"
        if not isinstance(message, str):
            message = "请根据预览范围确认操作。"
        files = data.get("affectedFiles")
        if isinstance(files, list):
            names = []
            for item in files[:50]:
                if not isinstance(item, dict) or not isinstance(item.get("originFileName"), str):
                    continue
                name = item["originFileName"].replace("\n", " ").replace("\r", " ")[:200]
                for symbol in "\\`*_{}[]<>()!#":
                    name = name.replace(symbol, "\\" + symbol)
                names.append("- " + name)
            if names:
                message += "\n\n本次照片范围：\n" + "\n".join(names)
        warnings = data.get("warnings")
        if isinstance(warnings, list):
            message += "\n" + "；".join(value for value in warnings if isinstance(value, str))
        result.update(message=message, publicMessage=message)
    except (ValueError, TypeError):
        pass
    return result
