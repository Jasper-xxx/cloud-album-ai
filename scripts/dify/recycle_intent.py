def guard_recycle_intent(data, query):
    """Recognize only complete album-photo commands; ambiguous scope stays unconfirmed."""
    import re

    query = re.sub(r"[。.!！?？]+$", "", query.strip())
    data["allRecycleImages"] = False
    # The action determines its endpoint; the model's family label is not authoritative.
    p3 = {"build_image_features", "update_location", "restore_files", "rename_person", "hide_people", "show_people", "move_person_files", "merge_people"}
    p4 = {"create_file_share_link", "create_album_share_link", "create_file_download_token", "create_album_download_token", "move_files_to_recycle_bin", "delete_albums", "permanently_delete_files", "empty_recycle_bin"}
    if data.get("mode") == "preview" and data.get("extendedAction") in p3 | p4:
        data["family"] = "p3_action" if data["extendedAction"] in p3 else "p4_action"
    recycle_scope = r'回收站(?:里边|里面|里|中)?的?(?:所有|全部)?(?:照片|图片)'
    restore_all = re.fullmatch(r'(?:请|请帮我|帮我)?(?:把|将)?' + recycle_scope + r'(?:全部|都)?(?:恢复|还原)(?:出来)?', query)
    restore_all = restore_all or re.fullmatch(r'(?:请|请帮我|帮我)?(?:恢复|还原)' + recycle_scope, query)
    if restore_all:
        data.update(mode="preview", family="p3_action", extendedAction="restore_files", allRecycleImages=True,
                    fileIds=[], albumIds=[], albumName="", albumAction="", tagAction="", aiAction="", reason="")
    elif data.get("extendedAction") == "restore_files" and data.get("mode") == "preview":
        if not data.get("fileIds") or re.search(r'不要|别|不想|如何|怎么|能否|是否|吗', query):
            data.update(mode="message", family="none", reason="请明确要恢复的照片范围；恢复回收站全部图片可发送「把回收站的照片恢复」。")

    delete_words = r"删除|删掉|删去|移入回收站|放入回收站"
    has_delete = bool(re.search(delete_words, query))
    permanent = bool(re.search(r"永久|彻底|不可恢复|清空回收站", query))
    negative_or_question = bool(re.search(r"不要|别|不想|不需要|不删除|不删|如何|怎么|能否|是否|能不能|可以.*吗", query))
    # Do not resolve filtered subsets (e.g. 最新两张/猫/视频) as the entire album.
    name = r'[“"「『]?([^“”"「」『』\n]{1,100}?)[”"」』]?'
    scope = r'相册\s*' + name + r'\s*(?:里边|里面|里|中)\s*的?\s*(?:所有|全部)?\s*(?:图片|照片)'
    match = re.fullmatch(r'(?:请|帮我|请帮我)?\s*(?:把|将)?\s*' + scope + r'\s*(?:都|全部)?\s*(?:' + delete_words + r')', query)
    if not match:
        match = re.fullmatch(r'(?:请|帮我|请帮我)?\s*(?:' + delete_words + r')\s*' + scope, query)
    if match and not permanent and not negative_or_question:
        album_name = match.group(1).strip()
        # Multiple names and qualifiers need an explicit scope, not a broad guess.
        if not re.search(r'以及|还有|和|、|，|,|除了|但|只', album_name):
            data.update(mode="preview", family="p4_action", extendedAction="move_files_to_recycle_bin",
                        albumName=album_name, fileIds=[], albumIds=[], includePictures=False,
                        albumAction="", tagAction="", aiAction="", reason="")
        else:
            data.update(mode="message", family="none", reason="相册范围有歧义，请一次明确一个相册及照片范围。")
    if has_delete and not permanent and not negative_or_question and data.get("extendedAction") == "permanently_delete_files":
        data.update(mode="message", family="none", extendedAction="",
                    reason="普通删除应移入回收站。请说明要处理的相册名称或照片范围，以便生成可恢复的预览。")
    if has_delete and negative_or_question and data.get("mode") == "preview":
        data.update(mode="message", family="none", reason="尚未创建删除预览。删除图片会先移入回收站；需要操作时请明确照片范围。")
    if has_delete and data.get("mode") == "preview" and data.get("extendedAction") == "move_files_to_recycle_bin" and data.get("albumName") and not match:
        data.update(mode="message", family="none", reason="请明确相册及图片范围；带筛选条件的请求需要先查询具体照片。")
    if has_delete and data.get("mode") in {"none", "message"}:
        data.update(mode="message", family="none",
                    reason="尚未创建待确认操作。请明确相册名称和照片范围；普通删除会先移入回收站，收到实际预览后才能确认执行。")
    return data
