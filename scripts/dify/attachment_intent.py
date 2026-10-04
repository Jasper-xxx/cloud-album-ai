def main(query=""):
    import re
    import unicodedata
    value = unicodedata.normalize("NFKC", query if isinstance(query, str) else "").strip()
    value = re.sub(r"[。.!！?？]+$", "", value).strip().lower()

    negative = bool(re.search(r"不要|别|不想|不需要|无需|禁止", value))
    save = bool(re.search(
        r"(?:保存|存入|存到|上传|加入|添加|收进).{0,12}(?:附件|这张|该张|图片|照片|图库)"
        r"|(?:附件|这张|该张|图片|照片).{0,12}(?:保存|存入|存到|上传|加入|添加|收进)(?:图库|相册)?",
        value,
    ))
    search = bool(re.search(
        r"以图搜图|相似度|(?:查找|寻找|搜索|搜|找).{0,18}(?:相似|相近|相像|类似|最像|同款|重复|相同).{0,10}(?:图片|照片|图)?"
        r"|(?:相似|相近|相像|类似|最像|同款|重复|相同).{0,12}(?:图片|照片|图)"
        r"|(?:用|拿|根据|通过).{0,8}(?:附件|这张图|这张图片|这张照片).{0,12}(?:查找|寻找|搜索|搜|找)",
        value,
    ))

    # One attachment turn performs one operation. Combined or negated requests need
    # clarification instead of silently saving or broadening the user's authority.
    other_write = bool(re.search(r"删除|删掉|移除|清空|清除|回收|恢复|分享|下载|创建|新建|重命名|改名|移动|打标签|加标签", value))
    question = bool(re.search(r"如何|怎么|怎样|是否|能否|可否|可以吗|能不能", value))
    if negative or question or (save and (search or other_write)):
        return {"mode": "clarify"}
    if save:
        return {"mode": "save"}
    if search or value in {"仅查询附件", "用附件查找照片", "查找这张图片"}:
        return {"mode": "search"}
    return {"mode": "clarify"}
