"""Local, read-only entry-point verification. Never print credentials or photo payloads."""
import json
import os
from pathlib import Path
from datetime import datetime, timezone
import httpx
import pymysql

ROOT = Path(__file__).resolve().parents[1]
runtime = json.loads((ROOT / "tmp/agent-local-runtime.json").read_text(encoding="utf-8"))
checks = []

def check(name, condition, **evidence):
    checks.append({"name": name, "passed": bool(condition), **evidence})
    print(name, "PASS" if condition else "FAIL")

with httpx.Client(trust_env=False, timeout=20) as client:
    path = "http://127.0.0.1:8088/agent/searchFiles"
    anonymous = client.post(path, json={"size": 1})
    check("anonymous_library_denied", anonymous.status_code == 401, httpStatus=anonymous.status_code)
    forged = client.post(path, json={"size": 1}, headers={"X-Agent-Service-Key": "forged"})
    check("forged_tool_denied", forged.status_code == 403, httpStatus=forged.status_code)
    valid = client.post(path, json={"size": 1}, headers={"X-Agent-Service-Key": runtime["AGENT_SERVICE_KEY"]})
    check("configured_owner_service_allowed", valid.status_code == 200 and valid.json().get("code") == 200, httpStatus=valid.status_code)
    ai = client.post("http://127.0.0.1:5000/recognize_from_minio", json={"object_key": "not-read"})
    check("anonymous_ai_denied", ai.status_code == 401, httpStatus=ai.status_code)
    url = client.post("http://127.0.0.1:5000/recognize", json={"url": "http://169.254.169.254/latest/meta-data/"}, headers={"X-AI-Service-Key": runtime["AI_SERVICE_KEY"]})
    check("authenticated_remote_url_disabled", url.status_code == 400, httpStatus=url.status_code)

with pymysql.connect(host="127.0.0.1", user=os.environ.get("DB_USERNAME", "root"), password=os.environ["DB_PASSWORD"], database="memory_space") as connection:
    with connection.cursor() as cursor:
        cursor.execute("SELECT version,success FROM flyway_schema_history WHERE version IN ('9','10','11','12')")
        migrations = dict(cursor.fetchall())
        check("local_migrations_applied", len(migrations) == 4 and all(migrations.values()), versions=sorted(migrations))
        cursor.execute("SELECT u.account,COUNT(uf.id) FROM user u LEFT JOIN user_file uf ON u.id=uf.user_id WHERE u.account IN ('album_test_a','album_test_b') GROUP BY u.account")
        counts = dict(cursor.fetchall())
        check("test_photo_relations_preserved", counts == {"album_test_a": 10, "album_test_b": 10}, counts=counts)

report = {"observedAt": datetime.now(timezone.utc).isoformat(), "scope": "local_entry_points_and_test_accounts", "checks": checks}
output = ROOT / "evaluation/observations/agent-repair-entry-smoke.json"
output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
raise SystemExit(0 if all(item["passed"] for item in checks) else 1)
