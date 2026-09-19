"""One valid query-only attachment; one feature-model request, no album upload."""
import io
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import httpx
import pymysql
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
runtime = json.loads((ROOT / "tmp/agent-local-runtime.json").read_text(encoding="utf-8"))
checks = []
with pymysql.connect(host="127.0.0.1", user=os.environ.get("DB_USERNAME", "root"),
                     password=os.environ["DB_PASSWORD"], database="memory_space", autocommit=True) as db:
    def rows(query):
        with db.cursor() as cursor:
            cursor.execute(query)
            return cursor.fetchall()
    query = "SELECT u.account,uf.file_id,uf.is_deleted FROM user_file uf JOIN user u ON u.id=uf.user_id WHERE u.account IN ('album_test_a','album_test_b') ORDER BY u.account,uf.file_id"
    before = rows(query)
    owned = {row[1] for row in before if row[0] == "album_test_a" and row[2] == 0}
    picture = Image.new("RGB", (64, 64), "white")
    for x in range(64):
        for y in range(64):
            picture.putpixel((x, y), (x * 4, y * 4, 128))
    buffer = io.BytesIO()
    picture.save(buffer, format="PNG")
    try:
        with httpx.Client(trust_env=False, timeout=90) as client:
            response = client.post("http://127.0.0.1:8088/agent/searchByAttachment",
                                   params={"conversationId": "delivery-attachment-query"},
                                   headers={"X-Agent-Service-Key": runtime["AGENT_SERVICE_KEY"]},
                                   files={"attachment": ("agent-query-fixture.png", buffer.getvalue(), "image/png")})
        result = response.json()
        checks.append({"name": "valid_attachment_query", "passed": result.get("code") == 200 and isinstance(result.get("data"), list),
                       "httpStatus": response.status_code, "businessCode": result.get("code")})
        if checks[-1]["passed"]:
            matches = result["data"]
            checks.append({"name": "attachment_results_owned_by_test_a", "passed": all(item.get("fileId") in owned for item in matches), "resultCount": len(matches)})
    finally:
        checks.append({"name": "attachment_did_not_save_or_change_test_relations", "passed": rows(query) == before})
        report = {"observedAt": datetime.now(timezone.utc).isoformat(), "input": "synthetic_64x64_png_query_only", "checks": checks}
        (ROOT / "evaluation/observations/agent-repair-attachment-smoke.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        for check in checks:
            print(check["name"], "PASS" if check["passed"] else "FAIL", flush=True)
raise SystemExit(0 if all(check["passed"] for check in checks) else 1)
