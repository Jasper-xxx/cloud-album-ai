"""Current-message scope -> verified read evidence -> one Preview, never Execute.

The deliberately bounded Chinese grammar rejects unconsumed scope text. This is
an authorization contract, not an LLM's interpretation of a previous selection.
No conversation variables or confirmation credentials enter these functions.
"""
import json
import re
import time
from read_loop_validate import _v_parameters, _v_user_constraints, _v_require
from read_loop_observation import safe_text


_B_NAME = r'(?:「([^「」\n]{1,100})」|“([^“”\n]{1,100})”|"([^"\n]{1,100})"|([\w\u4e00-\u9fff-]{1,100}))'
_B_QUOTED = r'(?:「([^「」\n]{1,100})」|“([^“”\n]{1,100})”|"([^"\n]{1,100})")'
_B_ALBUM_ACTIONS = {"create_album_and_add_files", "add_files_to_album"}
_B_TAG_ACTIONS = {"add_tags", "remove_tags"}


def _b_name(match):
    return next(value for value in match.groups() if value is not None).strip()


def _b_scope(scope):
    """Consume every supported selector; an unknown modifier cannot disappear."""
    params = {"mediaTypes": ["picture"]}
    rest = scope

    def consume(pattern, assign):
        nonlocal rest
        matches = list(re.finditer(pattern, rest))
        for match in matches:
            assign(match)
        rest = re.sub(pattern, " ", rest)

    def put(key, value):
        if key in params and params[key] != value:
            raise ValueError("CONFLICTING_SCOPE")
        params[key] = value

    count = []
    consume(r'(?:前|最多|至多)\s*([1-9]\d{0,2})\s*张', lambda m: count.append(int(m[1])))
    if len(count) > 1:
        raise ValueError("AMBIGUOUS_COUNT")
    limit = count[0] if count else 0
    if limit > 60:
        raise ValueError("SCOPE_LIMIT")
    for label, key in (("国家", "country"), ("省份", "province"), ("城市", "city"), ("区县", "district"), ("设备品牌", "make"), ("设备型号", "model"), ("关键词", "keyword")):
        consume(label + r'\s*(?:为|是|：|:)?\s*' + _B_QUOTED, lambda m, key=key: put(key, _b_name(m)))
    consume(r'(?:普通)?相册\s*' + _B_QUOTED + r'\s*(?:里面|里|中)', lambda m: put("albumName", _b_name(m)))
    consume(r'(?:标签为|带有标签|带标签|标签)\s*' + _B_QUOTED, lambda m: put("tags", [_b_name(m)]))
    # Consume named selectors first: a tag/album called 北京 is not a city filter.
    # Bare geographic shorthand is limited to these four unambiguous examples.
    consume(r'北京|上海|天津|重庆', lambda m: put("city", m[0]))
    if "tags" in params:
        params["tagOperator"] = "OR"
    consume(r'按上传时间', lambda m: (put("dateField", "uploaded"), put("orderBy", "uploadedAt")))
    consume(r'按拍摄时间', lambda m: (put("dateField", "taken"), put("orderBy", "takenAt")))
    for pattern, key, value in (
        (r'未标签|无标签|没有标签|未打标签', "tagState", "untagged"),
        (r'已有标签|已标签', "tagState", "tagged"),
        (r'未加入普通相册', "normalAlbumState", "excluded"),
        (r'已加入普通相册', "normalAlbumState", "included"),
        (r'缺少地理位置|没有地理位置|缺少GPS|无GPS', "locationState", "missing"),
        (r'有地理位置|带GPS|有GPS', "locationState", "present"),
        (r'缺少特征', "featureState", "missing"),
        (r'缺少AI分析', "aiAnalysisState", "missing"),
        (r'上传时间', "dateField", "uploaded"),
        (r'拍摄时间', "dateField", "taken"),
    ):
        consume(pattern, lambda m, key=key, value=value: put(key, value))
    relative = []
    consume(r'最近\s*[1-9]\d{0,2}\s*天|今天|昨天|今年|去年', lambda m: relative.append(m[0]))
    if len(relative) > 1:
        raise ValueError("AMBIGUOUS_DATE")
    # Relative dates are fixed by the existing date validator at initialization.
    explicit_dates = []
    consume(r'(\d{4}-\d{2}-\d{2})\s*(?:至|到)\s*(\d{4}-\d{2}-\d{2})', lambda m: explicit_dates.append((m[1], m[2])))
    if len(explicit_dates) > 1 or (relative and explicit_dates):
        raise ValueError("AMBIGUOUS_DATE")
    if explicit_dates:
        params.update(dateFrom=explicit_dates[0][0], dateTo=explicit_dates[0][1])
    # Delete grammar-only words after selectors. No arbitrary keyword fallback.
    rest = re.sub(r'请帮我|帮我|请|先|查找|查询|搜索|检索|筛选|找出|查|照片|图片|所有|全部|匹配|的|且|以及|和|在|，|,|。|；|;|\s+', '', rest)
    if rest or not re.search(r'照片|图片', scope):
        raise ValueError("UNSUPPORTED_OR_AMBIGUOUS_SCOPE")
    if "tags" in params and params.get("tagState") == "untagged":
        raise ValueError("CONFLICTING_SCOPE")
    if re.search(r'所有|全部', scope) and limit:
        raise ValueError("AMBIGUOUS_COUNT")
    return {"parameters": params, "rangeMode": "first_n" if limit else "all", "limit": limit}


def bridge_entry(query, parsed_mode, config):
    result = {"mode": "legacy", "scopeQuery": query, "bridge": "", "message": ""}
    # These modes have already passed the preserved deterministic control gates.
    if parsed_mode in {"execute", "cancel", "status", "agent_status"} or not isinstance(query, str):
        return result
    text = re.sub(r'[。.!！]+$', '', query.strip()).strip()
    album_verb = r'整理到|加入|加进|放入|放到|添加到|收进|移入'
    tag_verb = r'添加标签|增加标签|加上标签|打上标签|移除标签|去掉标签|删除标签'
    split = re.fullmatch(r'(?:请帮我|帮我|请)?\s*(?:把|将)?\s*(.+?(?:照片|图片)(?:[^\n]*?))\s*(?:，|,|；|;)?\s*(?:然后|再)?\s*(?:把这些照片|将这些照片|给这些照片|给它们|把它们)?\s*(' + album_verb + '|' + tag_verb + r')\s*(.+)', text)
    if not split:
        # A compound search/write request must not fall through to a broad legacy
        # selector when the supported grammar could not freeze the scope.
        compound = re.search(r'查找|查询|搜索|筛选|先查', text) and re.search(album_verb + '|' + tag_verb, text)
        if compound:
            result.update(mode="message", message="尚未创建预览。请一次明确照片筛选范围、一个普通相册或一个标签操作。")
        return result
    scope, verb, destination = split.groups()
    if not re.search(r'查找|查询|搜索|检索|筛选|先查|未标签|无标签|没有标签|未打标签|已有标签|所有|全部|前\s*\d|最多|至多|北京|上海|天津|重庆|国家|省份|城市|区县|普通相册|相册\s*[「“"]|标签\s*[「“"]|去年|今年|今天|昨天|最近\s*\d+\s*天|\d{4}-\d{2}-\d{2}|缺少|GPS|设备|关键词', scope):
        return result
    result.update(mode="message", message="尚未创建预览。范围未能完整解析，请使用明确筛选条件和一个目标；例如「先查找北京未标签的照片，再整理到相册「测试旅行」」。")
    if len(query) > 2000 or re.search(r'不要|不想|请勿|不需要|如何|怎么|是否|能否|能不能|吗|？|\?|刚才|上一次|回收站|附件|相似|AI标签|人物', text):
        return result
    try:
        frozen = _b_scope(scope)
        if verb in {"整理到", "加入", "加进", "放入", "放到", "添加到", "收进", "移入"}:
            match = re.fullmatch(r'(?:普通)?相册\s*' + _B_NAME, destination)
            if not match:
                match = re.fullmatch(_B_NAME + r'\s*(?:普通)?相册', destination)
            if not match:
                return result
            target = _b_name(match)
            frozen.update(family="album", action="create_album_and_add_files", target=target)
        else:
            match = re.fullmatch(_B_NAME, destination)
            if not match:
                return result
            frozen.update(family="tag", action="remove_tags" if verb in {"移除标签", "去掉标签", "删除标签"} else "add_tags", target=_b_name(match))
        if not frozen["target"] or len(frozen["target"].encode("utf-16-le")) // 2 > 30 or safe_text(frozen["target"], 100) != frozen["target"] or re.search(r'和|以及|、|，|,|;|；|\{|\}|\[|\]|\x00|\n', frozen["target"]):
            return result
        cfg = json.loads(config)
        if cfg.get("enabled") is not True or cfg.get("writePreviewEnabled") is not True:
            result["message"] = "查询后写预览开关已关闭，本次没有创建预览。请单独查询或启用开关后重新发送完整请求。"
            return result
        frozen.update(version=1, scopeQuery=scope.strip())
        result.update(mode="need_scope", scopeQuery=scope.strip(), bridge=json.dumps(frozen, ensure_ascii=False, sort_keys=True, separators=(",", ":")), message="")
    except (ValueError, TypeError, KeyError):
        pass
    return result


def _b_expected(bridge, state):
    # Use the same DTO/type/date checks as reads; not model-supplied state.
    params = _v_parameters("advancedSearchFiles", bridge["parameters"], state, allow_refs=False)
    _v_user_constraints("advancedSearchFiles", params, state)
    limit = bridge["limit"]
    if limit:
        params["size"] = max(n for n in range(1, min(params["size"], limit) + 1) if limit % n == 0)
    goals = ["search_photos"]
    if params["albumName"]:
        goals.insert(0, "resolve_album")
    if bridge["family"] == "album":
        goals.insert(0, "resolve_destination")
    return {"goals": goals, "constraints": {"tool": "advancedSearchFiles", "parameters": params}, "limit": limit, "allowTagAlternatives": []}


def _b_check_task(task, state):
    if not state.get("bridge"):
        return
    expected = state["bridge"]["expectedTask"]
    # Duplicate top-level resource names are allowed only as exact echoes.
    constraints = dict(task["constraints"])
    for name in ("albumName", "personName"):
        if name in constraints:
            _v_require(constraints.pop(name) == expected["constraints"]["parameters"][name], "BRIDGE_SCOPE_CHANGED")
    _v_require(dict(task, constraints=constraints) == expected, "BRIDGE_SCOPE_CHANGED")


def bridge_preview(state):
    """Revalidate request-local provenance after Loop exits; output native arrays."""
    state = json.loads(state)
    output = {"family": "none", "action": "", "albumId": -1, "albumName": "", "tagName": "", "fileIds": [],
              "imageType": "其他", "imageTypeText": "picture", "searchType": "", "searchKeyword": "", "mediaType": "", "sourceTagName": "", "locationLevel": "", "locationValue": "", "sourceAlbumId": -1, "selectionLimit": 1,
              "message": "查询范围未完整确认，本次没有创建新的预览。请缩小范围并重新发送完整请求。"}
    bridge = state.get("bridge")
    if not bridge:
        output["message"] = ""
        return output
    if state["status"] != "COMPLETED" or state["truncated"] or time.time() - state["startedAt"] >= state["cfg"]["softDeadlineSeconds"]:
        return output
    pages, refs = bridge.get("photoPages", []), bridge.get("photoRefs", [])
    if not pages or not refs or len(refs) != len(set(refs)) or len(refs) > min(100, state["cfg"]["maxCollectedRecords"]):
        return output
    wanted = pages[0]["total"] if bridge["rangeMode"] == "all" else min(bridge["limit"], pages[0]["total"])
    if len(refs) != wanted or (bridge["rangeMode"] == "all" and pages[-1]["hasNext"]):
        return output
    if set(state["completedGoals"]) != set(bridge["expectedTask"]["goals"]):
        return output
    # Recheck the complete page chain and its observed projections, instead of
    # trusting a completed status or a planner's assertion about completeness.
    projected_refs = []
    for index, page in enumerate(pages, 1):
        obs = next((o for o in state["observations"] if o["ref"] == page.get("observationRef")), {})
        if page["current"] != index or any(page[key] != pages[0][key] for key in ("size", "total", "pages")) or not obs.get("ok") or obs.get("tool") != "advancedSearchFiles" or obs.get("pagination") != {key: value for key, value in page.items() if key != "observationRef"}:
            return output
        if len(obs.get("records", [])) != obs.get("count") or obs.get("count") != page["returnedCount"]:
            return output
        projected_refs.extend(r.get("recordRef") for r in obs["records"])
    if projected_refs != refs:
        return output
    files = []
    for reference in refs:
        record = state["evidenceMap"].get(reference, {})
        obs = next((o for o in state["observations"] if o["ref"] == record.get("observationRef")), {})
        if record.get("kind") != "photo" or not obs.get("ok") or obs.get("tool") != "advancedSearchFiles" or not any(r.get("recordRef") == reference for r in obs.get("records", [])):
            return output
        identifier = record.get("id")
        if type(identifier) is not str or not re.fullmatch(r'[A-Za-z0-9_-]{1,36}', identifier) or identifier in files:
            return output
        files.append(identifier)
    if bridge["family"] == "album":
        if bridge.get("destinationResolved") is not True or bridge["action"] not in _B_ALBUM_ACTIONS:
            return output
        output.update(albumId=bridge.get("destinationId", -1), albumName=bridge["target"])
    elif bridge["family"] == "tag":
        if bridge["action"] not in _B_TAG_ACTIONS:
            return output
        output["tagName"] = bridge["target"]
    else:
        return output
    output.update(family=bridge["family"], action=bridge["action"], fileIds=files, message="")
    return output


def bridge_album_result(raw):
    return {"raw": raw, "family": "album"}


def bridge_tag_result(raw):
    return {"raw": raw, "family": "tag"}
