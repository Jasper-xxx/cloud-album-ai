"""Scan deliverable files/index/build for local secret values; never print values."""
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
runtime_path = ROOT / "tmp/agent-local-runtime.json"
runtime = json.loads(runtime_path.read_text(encoding="utf-8")) if runtime_path.exists() else {}
secret_names = re.compile(r"(?:PASSWORD|SECRET|API_KEY|SERVICE_KEY|ACCESS_KEY|AUTH_TOKEN)$")
secrets = {name: value.encode() for name, value in (dict(os.environ) | runtime).items()
           if secret_names.search(name) and isinstance(value, str) and len(value) >= 8}
paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode().split("\0")
files = {ROOT / name for name in paths if name}
files.update((ROOT / "frontend/dist").rglob("*.js"))
files.update((ROOT / "frontend/dist").rglob("*.html"))
findings = []
for path in sorted(files):
    if not path.is_file():
        continue
    content = path.read_bytes()
    for name, value in secrets.items():
        if value in content:
            findings.append({"path": path.relative_to(ROOT).as_posix(), "secretName": name})
index = subprocess.check_output(["git", "diff", "--cached", "--no-ext-diff", "--no-color"], cwd=ROOT)
for name, value in secrets.items():
    if value in index:
        findings.append({"path": "git-index-diff", "secretName": name})
ignored = subprocess.run(["git", "check-ignore", "-q", "tmp/agent-local-runtime.json"], cwd=ROOT).returncode == 0
report = {"observedAt": datetime.now(timezone.utc).isoformat(), "filesScanned": len(files),
          "configuredSecretValuesChecked": len(secrets), "runtimeCredentialFileIgnored": ignored,
          "findings": findings, "passed": not findings and ignored,
          "scope": "Known local secret values in tracked/unignored files, staged diff and frontend JS/HTML build. Not a full historical repository credential audit."}
(ROOT / "evaluation/observations/agent-repair-secret-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
raise SystemExit(0 if report["passed"] else 1)
