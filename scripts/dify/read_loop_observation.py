"""Bounded, untrusted tool-response adapters for the read-only Dify loop.

Source contracts: AgentController, FileInfoListVO/FileInfo, album VOs and the
Agent* result VOs. No business modules are imported: the workflow generator
embeds this source in a Code node. The caller replaces IDs with recordRef before
showing records to a model. A success envelope never authorizes another tool.
"""

import json
import re


_O_TOOLS = frozenset((
    "getAgentCapabilities", "searchFiles", "advancedSearchFiles", "listAlbums",
    "listLocationAlbums", "listModelAlbums", "listTags", "listPeople",
    "analyzeLibrary",
))
_O_MAX_INPUT_BYTES = 524288
_O_MAX_RECORDS = 1000
_O_LONG_MAX = 9223372036854775807


class _ObservationError(ValueError):
    pass


def safe_text(value, limit=240):
    """Return bounded display data; never stringify arbitrary objects/secrets.

    This is deliberately conservative. If a name changes, adapters flag it as
    unsafeName so redaction/truncation cannot create a false exact-name match.
    Text is still untrusted data, not a source of planner instructions.
    """
    if not isinstance(value, str):
        return ""
    limit = max(0, min(limit, 4096)) if type(limit) is int else 240
    if len(value) > 16384:
        return "[过长文本已省略]"[:limit]
    if re.search(
        r"(?i)(?:feature[_ -]?vector|embedding|\bvector\s*[:=]|"
        r"traceback|stack\s*trace|(?:\w+\.)+(?:\w*Exception|\w*Error)|"
        r"(?:api[_ -]?key|service[_ -]?key|secret|password|authorization|"
        r"confirmation[_ -]?token|idempotency[_ -]?key|access[_ -]?token|"
        r"pending[_ -]?action[_ -]?id|签名|密钥|凭证|令牌)\s*[\"']?\s*[:=])",
        value,
    ):
        return "[敏感内容已省略]"[:limit]
    value = re.sub(r"(?i)\b(?:https?|s3|minio|file|ftp)://[^\s<>\"']+", "[链接已省略]", value)
    value = re.sub(r"(?i)\bwww\.[^\s<>\"']+", "[链接已省略]", value)
    value = re.sub(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+", "[凭据已省略]", value)
    value = re.sub(r"\b(?:sk-[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)\b", "[凭据已省略]", value)
    # Includes object keys such as bucket/user/image.jpg, drive and UNC paths.
    value = re.sub(r"(?:[A-Za-z]:[\\/]|\\\\|/)[^\s<>\"']+", "[路径已省略]", value)
    value = re.sub(r"\b[^\s<>\"'/:]+(?:/[^\s<>\"']+)+", "[路径已省略]", value)
    value = re.sub(r"\b[A-Za-z0-9_-]{40,}\b", "[不透明内容已省略]", value)
    value = re.sub(r"[\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]", " ", value)
    return value[:limit]


def _o_fail(category, code=None):
    return {"ok": False, "errorCategory": category, "businessCode": code,
            "records": [], "pagination": {}, "conditionSummary": "",
            "relaxSuggestions": [], "summary": {}, "repair": None}


def _o_success():
    result = _o_fail("", 200)
    result["ok"] = True
    return result


def _o_int(value, minimum=0, maximum=_O_LONG_MAX):
    if type(value) is not int or not minimum <= value <= maximum:
        raise _ObservationError("INVALID_RESULT")
    return value


def _o_string(value, required=False, limit=240):
    if value is None and not required:
        return ""
    if not isinstance(value, str) or (required and not value.strip()):
        raise _ObservationError("INVALID_RESULT")
    return safe_text(value, limit)


def _o_list(value, maximum=_O_MAX_RECORDS):
    if not isinstance(value, list):
        raise _ObservationError("INVALID_RESULT")
    if len(value) > maximum:
        raise _ObservationError("RESULT_TOO_LARGE")
    return value


def _o_dict(value):
    if not isinstance(value, dict):
        raise _ObservationError("INVALID_RESULT")
    return value


def _o_json_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise _ObservationError("CONFLICTING_ENVELOPES")
        result[key] = value
    return result


def _o_bad_constant(_value):
    raise _ObservationError("INVALID_RESULT")


def _o_envelope(raw):
    if isinstance(raw, str):
        if len(raw.encode("utf-8")) > _O_MAX_INPUT_BYTES:
            raise _ObservationError("RESULT_TOO_LARGE")
        raw = json.loads(raw, object_pairs_hook=_o_json_pairs, parse_constant=_o_bad_constant)
    else:
        serialized = json.dumps(raw, ensure_ascii=False, allow_nan=False)
        if len(serialized.encode("utf-8")) > _O_MAX_INPUT_BYTES:
            raise _ObservationError("RESULT_TOO_LARGE")
    # Dify custom API tools may expose a one-element json output array.
    if isinstance(raw, list):
        if len(raw) != 1:
            raise _ObservationError("CONFLICTING_ENVELOPES")
        raw = raw[0]
    raw = _o_dict(raw)
    code = _o_int(raw.get("code"), 100, 599)
    if code == 200 and "data" not in raw:
        raise _ObservationError("INVALID_RESULT")
    if code != 200:
        raw.setdefault("data", None)
    if "message" in raw and not isinstance(raw["message"], str):
        raise _ObservationError("INVALID_RESULT")
    return raw


def _o_transport_category(error):
    # Only classify the raw error. Never return it, even after attempted scrub.
    value = error if isinstance(error, str) else ""
    value = value[:8192].lower()
    if re.search(r"\b401\b|unauthorized|not.?logged.?in", value):
        return "AUTH_REQUIRED"
    if re.search(r"\b40[23]\b|forbidden|access denied", value):
        return "ACCESS_DENIED"
    if re.search(r"\b429\b|quota|rate.?limit|配额", value):
        return "RATE_LIMITED"
    if re.search(r"\b404\b|endpoint.?not.?found|no route", value):
        return "ENDPOINT_NOT_FOUND"
    if re.search(r"timeout|timed out|超时", value):
        return "TIMEOUT"
    if re.search(r"\b5\d\d\b", value):
        return "SERVER_ERROR"
    if re.search(r"connection|connect error|dns|name resolution|连接", value):
        return "CONNECTION_ERROR"
    if re.search(r"\b400\b", value):
        return "INVALID_PARAMETERS"
    return "TRANSPORT_ERROR"


def _o_business_category(code):
    return {400: "INVALID_PARAMETERS", 401: "AUTH_REQUIRED",
            402: "ACCESS_DENIED", 403: "ACCESS_DENIED",
            404: "RESOURCE_NOT_FOUND", 429: "RATE_LIMITED"}.get(
                code, "SERVER_ERROR" if code >= 500 else "BUSINESS_FAILURE")


def _o_id(value, numeric=False):
    if numeric:
        if isinstance(value, str) and re.fullmatch(r"[1-9][0-9]{0,18}", value):
            return str(_o_int(int(value), 1))
        return str(_o_int(value, 1))
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise _ObservationError("INVALID_RESULT")
    return value


def _o_record(kind, identifier, raw_name):
    name = _o_string(raw_name, required=True)
    record = {"id": identifier, "kind": kind, "name": name}
    if name != raw_name:
        record["unsafeName"] = True
    return record


def _o_optional_fields(source, record, strings=(), counts=(), booleans=()):
    for key in strings:
        if key in source and source[key] is not None:
            record[key] = _o_string(source[key])
    for key in counts:
        if key in source and source[key] is not None:
            record[key] = _o_int(source[key])
    for key in booleans:
        if key in source and source[key] is not None:
            if type(source[key]) is not bool:
                raise _ObservationError("INVALID_RESULT")
            record[key] = source[key]


def _o_photo(row, advanced=False):
    row = _o_dict(row)
    record = _o_record("photo", _o_id(row.get("fileId")), row.get("originFileName"))
    _o_optional_fields(row, record, counts=("size", "width", "height", "duration"))
    if row.get("category") is not None:
        category = row["category"]
        if category not in {"image", "video", "doc", "audio", "other"}:
            raise _ObservationError("INVALID_RESULT")
        record["category"] = category
    if row.get("contentType") is not None:
        if not isinstance(row["contentType"], str) or not re.fullmatch(r"[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+", row["contentType"]):
            raise _ObservationError("INVALID_RESULT")
        record["contentType"] = row["contentType"][:100]
    if advanced:
        _o_optional_fields(row, record,
                           strings=("takenAt", "uploadedAt", "make", "model", "country", "province", "city", "district"),
                           booleans=("hasLocation", "hasFeature", "hasAiAnalysis"))
        record["tags"] = [_o_string(tag, required=True, limit=80) for tag in _o_list(row.get("tags"), 100)]
    return record


def _o_album(row):
    row = _o_dict(row)
    record = _o_record("album", _o_id(row.get("albumId"), numeric=True), row.get("albumName"))
    # selectAllAlbum is the normal-album query. An explicit different type
    # cannot be repurposed as a normal album evidence reference.
    if row.get("type") not in (None, "normal"):
        raise _ObservationError("INVALID_RESULT")
    _o_optional_fields(row, record, counts=("imageCount", "videoCount"), strings=("createTime", "updateTime"))
    return record


def _o_location(row):
    row = _o_dict(row)
    if row.get("locationLevel") not in {"country", "province", "city", "district"}:
        raise _ObservationError("INVALID_RESULT")
    record = _o_record("location", None, row.get("locationValue"))
    record.update(locationLevel=row["locationLevel"], locationValue=record["name"], total=_o_int(row.get("total")))
    return record


def _o_model(row):
    row = _o_dict(row)
    record = _o_record("model", None, row.get("modelName"))
    record.update(modelName=record["name"], makeName=_o_string(row.get("makeName")), total=_o_int(row.get("total")))
    if record["makeName"] != (row.get("makeName") or ""):
        record["unsafeName"] = True
    return record


def _o_person(row):
    row = _o_dict(row)
    # Backend names may be null before a person group is named. Keep that
    # group visible but do not invent a name that could match user input.
    raw_name = row.get("personName")
    if raw_name is None or raw_name == "":
        record = {"id": _o_id(row.get("personId"), numeric=True), "kind": "person", "name": "", "unsafeName": True}
    else:
        record = _o_record("person", _o_id(row.get("personId"), numeric=True), raw_name)
    _o_optional_fields(row, record, counts=("total",), strings=("createTime",))
    return record


def _o_tag(row):
    row = _o_dict(row)
    record = _o_record("tag", None, row.get("tagName"))
    record.update(tagName=record["name"], count=_o_int(row.get("count")))
    return record


def _o_pagination(data, returned_count, advanced=False):
    current = _o_int(data.get("current"), 1, 2147483647)
    size = _o_int(data.get("size"), 1, 1000)
    total = _o_int(data.get("total"))
    pages = _o_int(data.get("pages"))
    if pages != (total + size - 1) // size:
        raise _ObservationError("INVALID_RESULT")
    has_next = current < pages
    if advanced or "hasNext" in data:
        if type(data.get("hasNext")) is not bool or data["hasNext"] != has_next:
            raise _ObservationError("INVALID_RESULT")
    # Page counts refer to SQL rows (files), not date buckets. Incomplete
    # pages must not establish a unique album/person-name match.
    expected = max(0, min(size, total - (current - 1) * size))
    if returned_count != expected:
        raise _ObservationError("INVALID_RESULT")
    return {"current": current, "size": size, "total": total, "pages": pages,
            "hasNext": has_next, "returnedCount": returned_count, "truncated": False}


def _o_relax_suggestions(value):
    codes = {"tags_and_to_or", "expand_date_range", "relax_location", "remove_album_scope",
             "remove_completeness_filter", "remove_keyword"}
    result = []
    for row in _o_list(value, 10):
        row = _o_dict(row)
        if row.get("code") not in codes:
            raise _ObservationError("INVALID_RESULT")
        result.append({"code": row["code"], "message": _o_string(row.get("message"), required=True)})
    return result


def _o_capabilities(data):
    data = _o_dict(data)
    summary = {"name": _o_string(data.get("name"), required=True),
               "mode": _o_string(data.get("mode"), required=True)}
    # These are descriptive output, never a replacement for the tool whitelist.
    for key in ("readOnlyTools", "directMutationTools", "confirmationRequiredTools", "disabledTools"):
        values = _o_list(data.get(key), 100)
        if any(not isinstance(value, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", value) for value in values):
            raise _ObservationError("INVALID_RESULT")
        summary[key] = values
    summary["riskRules"] = [_o_string(rule, required=True, limit=300) for rule in _o_list(data.get("riskRules"), 20)]
    return summary


def _o_analysis(data):
    data = _o_dict(data)
    summary = {"generatedAt": _o_string(data.get("generatedAt"), required=True, limit=64),
               "staleMinutes": _o_int(data.get("staleMinutes"), 1, 10080),
               "healthScore": _o_int(data.get("healthScore"), 0, 100)}
    if data.get("healthLevel") not in {"EMPTY", "EXCELLENT", "GOOD", "NEEDS_ATTENTION", "CRITICAL"}:
        raise _ObservationError("INVALID_RESULT")
    summary["healthLevel"] = data["healthLevel"]
    for key in ("totalFileCount", "untaggedFileCount", "missingLocationFileCount", "missingFeatureFileCount",
                "missingAiAnalysisFileCount", "similarGroupCount", "similarFileCount", "failedAiTaskCount",
                "staleAiTaskCount", "pendingAiTaskCount", "unalbumedFileCount"):
        summary[key] = _o_int(data.get(key))
    summary["suggestions"] = []
    for row in _o_list(data.get("suggestions"), 20):
        row = _o_dict(row)
        if row.get("priority") not in {"LOW", "MEDIUM", "HIGH"}:
            raise _ObservationError("INVALID_RESULT")
        suggestion = {"priority": row["priority"], "affectedCount": _o_int(row.get("affectedCount"))}
        for key in ("code", "title", "description", "recommendedAction"):
            suggestion[key] = _o_string(row.get(key), required=True, limit=300)
        filters = _o_dict(row.get("suggestedFilters"))
        safe_filters = {}
        # Exact fields currently generated by AgentLibraryService, not a
        # recursive pass-through of its Map<String,Object>.
        for key in ("missingLocation", "missingFeature", "missingAiAnalysis", "withoutNormalAlbum"):
            if key in filters:
                if type(filters[key]) is not bool:
                    raise _ObservationError("INVALID_RESULT")
                safe_filters[key] = filters[key]
        for key in ("staleMinutes", "similarGroupCount"):
            if key in filters:
                safe_filters[key] = _o_int(filters[key])
        if "tagState" in filters:
            if filters["tagState"] != "untagged":
                raise _ObservationError("INVALID_RESULT")
            safe_filters["tagState"] = "untagged"
        if "statuses" in filters:
            statuses = _o_list(filters["statuses"], 2)
            if any(status not in {"FAILED", "DEAD"} for status in statuses):
                raise _ObservationError("INVALID_RESULT")
            safe_filters["statuses"] = statuses
        suggestion["suggestedFilters"] = safe_filters
        summary["suggestions"].append(suggestion)
    return summary


def adapt_result(tool, text, json_value=None, transport_error=""):
    """Validate one real tool result; no retries, model calls or repairs.

    Nonempty transport_error always wins over apparent success. An exact
    duplicate text/json envelope is accepted; conflicting wrappers are rejected.
    Backend BaseResponse exposes no machine-checkable lossless repair contract,
    so repair is always None. Error messages are never passed through.
    """
    if tool not in _O_TOOLS:
        return _o_fail("UNSUPPORTED_TOOL")
    if transport_error:
        return _o_fail(_o_transport_category(transport_error))
    code = None
    try:
        envelopes = []
        if text not in (None, ""):
            envelopes.append(_o_envelope(text))
        if json_value not in (None, "", []):
            envelopes.append(_o_envelope(json_value))
        if not envelopes:
            return _o_fail("INVALID_RESULT")
        canonical = [json.dumps(item, sort_keys=True, ensure_ascii=False, allow_nan=False) for item in envelopes]
        if len(set(canonical)) != 1:
            return _o_fail("CONFLICTING_ENVELOPES")
        envelope = envelopes[0]
        code = envelope["code"]
        if code != 200:
            return _o_fail(_o_business_category(code), code)
        result = _o_success()
        data = envelope["data"]
        if tool == "getAgentCapabilities":
            result["summary"] = _o_capabilities(data)
            return result
        if tool == "analyzeLibrary":
            result["summary"] = _o_analysis(data)
            return result
        if tool == "listTags":
            result["records"] = [_o_tag(row) for row in _o_list(data)]
            count = len(result["records"])
            result["pagination"] = {"current": 1, "size": count, "total": count,
                                    "pages": 1 if count else 0, "hasNext": False,
                                    "returnedCount": count, "truncated": False}
        else:
            data = _o_dict(data)
            rows = _o_list(data.get("records"))
            if tool == "searchFiles":
                records = []
                for group in rows:
                    group = _o_dict(group)
                    # Java's Time field is normally serialized as time by
                    # Jackson/Lombok. Tolerate exported capital-Time spelling.
                    if "time" in group and "Time" in group and group["time"] != group["Time"]:
                        raise _ObservationError("INVALID_RESULT")
                    group_time = group.get("time", group.get("Time"))
                    if group_time is not None and (not isinstance(group_time, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", group_time)):
                        raise _ObservationError("INVALID_RESULT")
                    for row in _o_list(group.get("fileList")):
                        record = _o_photo(row)
                        if group_time is not None:
                            record["groupDate"] = group_time
                        records.append(record)
                        if len(records) > _O_MAX_RECORDS:
                            raise _ObservationError("RESULT_TOO_LARGE")
            elif tool == "advancedSearchFiles":
                records = [_o_photo(row, advanced=True) for row in rows]
                result["conditionSummary"] = _o_string(data.get("conditionSummary"), required=True, limit=1200)
                result["relaxSuggestions"] = _o_relax_suggestions(data.get("relaxSuggestions"))
            else:
                adapter = {"listAlbums": _o_album, "listLocationAlbums": _o_location,
                           "listModelAlbums": _o_model, "listPeople": _o_person}[tool]
                records = [adapter(row) for row in rows]
            result["records"] = records
            result["pagination"] = _o_pagination(data, len(records), tool == "advancedSearchFiles")
        identifiers = [(record["kind"], record["id"]) for record in result["records"] if record["id"] is not None]
        if len(set(identifiers)) != len(identifiers):
            raise _ObservationError("INVALID_RESULT")
        result["summary"] = {"returnedCount": len(result["records"])}
        return result
    except _ObservationError as error:
        return _o_fail(str(error), code)
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
        return _o_fail("INVALID_RESULT", code)
