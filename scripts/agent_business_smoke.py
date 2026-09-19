"""Authorized local test-account regression with independent database readback.

Searches, previews, cancels, and recycles/restores one existing test photo.
No permanent deletion, new album, public share, or model call.
Credentials and photo names/URLs are never written to the evidence report.
"""
import hashlib
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
import httpx
import pymysql

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = json.loads((ROOT / "tmp/agent-local-runtime.json").read_text(encoding="utf-8"))
RUN = uuid.uuid4().hex[:12]
RESUME = "--resume-write" in sys.argv
CHECKS = []
if RESUME:
    previous = json.loads((ROOT / "evaluation/observations/agent-repair-business-smoke.json").read_text(encoding="utf-8"))
    RUN = previous["runId"]
    CHECKS = [item for item in previous["checks"] if item["name"] != "original_photo_state_restored"]
DB = pymysql.connect(host="127.0.0.1", user=os.environ.get("DB_USERNAME", "root"),
                     password=os.environ["DB_PASSWORD"], database="memory_space", autocommit=True)
CLIENT = httpx.Client(base_url="http://127.0.0.1:8088", trust_env=False, timeout=30,
                     headers={"X-Agent-Service-Key": RUNTIME["AGENT_SERVICE_KEY"]})


def sql(query, args=()):
    with DB.cursor() as cursor:
        cursor.execute(query, args)
        return cursor.fetchall()


def check(name, condition, **evidence):
    if RESUME and any(item["name"] == name and item["passed"] for item in CHECKS):
        return
    CHECKS.append({"name": name, "passed": bool(condition), **evidence})
    print(name, "PASS" if condition else "FAIL", flush=True)
    if not condition:
        raise AssertionError(name)


def call(tool, body=None, convo="main", expected=200):
    response = CLIENT.post("/agent/" + tool, params={"conversationId": RUN + "-" + convo}, json=body or {})
    value = response.json()
    if value.get("code") != expected:
        raise AssertionError(f"{tool}: expected code {expected}, got {value.get('code')}: {value.get('message')}")
    return value.get("data")


def credentials(preview):
    return {key: preview[key] for key in ("pendingActionId", "confirmationToken", "idempotencyKey")} | {"confirmed": True}


def action(family, body, convo="main"):
    preview = call("preview" + family + "Action", body, convo)
    check(body["action"] + "_requires_confirmation", preview.get("requiresConfirmation") is True)
    result = call("execute" + family + "Action", credentials(preview), convo)
    check(body["action"] + "_explicit_success", result.get("success") is True)
    return result


def snapshot(user, file):
    return {
        "relation": sql("SELECT is_deleted FROM user_file WHERE user_id=%s AND file_id=%s", (user, file)),
        "tags": sql("SELECT tag_name,image_type FROM picture_tag WHERE file_id=%s ORDER BY tag_name,image_type", (file,)),
        "albums": sql("SELECT album_id,is_cover FROM album_picture WHERE user_id=%s AND file_id=%s ORDER BY album_id", (user, file)),
        "similar": sql("SELECT similar_id FROM similar_picture WHERE user_id=%s AND file_id=%s ORDER BY similar_id", (user, file)),
        "quota": sql("SELECT used_space FROM user_storage WHERE user_id=%s", (user,)),
    }


def main():
    accounts = dict(sql("SELECT account,id FROM user WHERE account IN ('album_test_a','album_test_b')"))
    user = accounts["album_test_a"]
    other = accounts["album_test_b"]
    check("configured_test_owner", int(RUNTIME["AGENT_OWNER_USER_ID"]) == user)
    rows = sql("SELECT uf.file_id FROM user_file uf JOIN file f ON f.file_id=uf.file_id WHERE uf.user_id=%s AND uf.is_deleted=0 AND f.category='image' AND NOT EXISTS(SELECT 1 FROM user_file x WHERE x.file_id=uf.file_id AND x.user_id<>%s) ORDER BY f.size LIMIT 1", (user, user))
    check("exclusive_test_photo_available", len(rows) == 1)
    photo = rows[0][0]
    other_photo = sql("SELECT file_id FROM user_file WHERE user_id=%s AND is_deleted=0 AND file_id NOT IN(SELECT file_id FROM user_file WHERE user_id=%s) LIMIT 1", (other, user))[0][0]
    original = snapshot(user, photo)
    other_original = snapshot(other, other_photo)
    def search_ids():
        page = call("searchFiles", {"size": 100})
        return {item["fileId"] for group in page["records"] for item in group["fileList"]}
    before = search_ids()
    owned = {row[0] for row in sql("SELECT file_id FROM user_file WHERE user_id=%s AND is_deleted=0", (user,))}
    check("search_matches_owned_active_database_rows", before == owned)
    check("search_excludes_other_account_photo", other_photo not in before)
    try:
        if not RESUME:
            first = call("previewAlbumAction", {"action": "create_album", "albumName": "repair-" + RUN}, "one")
            check("preview_has_confirmation_credentials", first.get("requiresConfirmation") is True and all(first.get(k) for k in ("pendingActionId", "confirmationToken", "idempotencyKey")))
            check("preview_does_not_create_album", not sql("SELECT album_id FROM album WHERE user_id=%s AND album_name=%s", (user, "repair-" + RUN)))
            call("executeAlbumAction", credentials(first), "two", expected=403)
            check("cross_conversation_execute_denied", True)
            call("executeAlbumAction", credentials(first) | {"confirmationToken": "z" * 43}, "one", expected=403)
            check("forged_confirmation_denied", True)
            cancelled = call("cancelPendingAction", {k: first[k] for k in ("pendingActionId", "confirmationToken")}, "one")
            check("cancel_database_readback", cancelled["status"] == "CANCELLED" and sql("SELECT status FROM agent_pending_action WHERE user_id=%s AND pending_action_id=%s", (user, first["pendingActionId"])) == (("CANCELLED",),))
        call("previewP4Action", {"action": "move_files_to_recycle_bin", "fileIds": [other_photo]}, expected=402)
        check("other_account_write_scope_rejected", True)
        moved = action("P4", {"action": "move_files_to_recycle_bin", "fileIds": [photo]})
        check("recycle_database_readback", moved["affectedFileCount"] == 1 and sql("SELECT is_deleted FROM user_file WHERE user_id=%s AND file_id=%s", (user, photo)) == ((1,),))
        check("recycled_photo_hidden_from_search", photo not in search_ids())
        restored = action("P3", {"action": "restore_files", "fileIds": [photo]})
        check("restore_is_lossless_database_readback", restored["affectedFileCount"] == 1 and snapshot(user, photo) == original)
        check("restored_photo_searchable", search_ids() == before)
        check("other_account_unchanged", snapshot(other, other_photo) == other_original)
        # Minimum multipart smoke: malformed image rejected before upload/model invocation.
        for tool in ("uploadAttachment", "searchByAttachment"):
            response = CLIENT.post("/agent/" + tool, params={"conversationId": RUN + "-attachment"},
                                   files={"attachment": ("invalid.png", b"not-an-image", "image/png")})
            check(tool + "_invalid_image_rejected", response.json().get("code") == 400)
        check("invalid_attachments_did_not_create_relations", search_ids() == before)
    finally:
        if sql("SELECT is_deleted FROM user_file WHERE user_id=%s AND file_id=%s", (user, photo)) == ((1,),):
            action("P3", {"action": "restore_files", "fileIds": [photo]}, "cleanup")
        check("original_photo_state_restored", snapshot(user, photo) == original)


if __name__ == "__main__":
    try:
        main()
    finally:
        report = {"runId": RUN, "observedAt": datetime.now(timezone.utc).isoformat(),
                  "evidenceSource": "real_local_http_and_independent_mysql_readback",
                  "checks": CHECKS}
        (ROOT / "evaluation/observations/agent-repair-business-smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        CLIENT.close()
        DB.close()
