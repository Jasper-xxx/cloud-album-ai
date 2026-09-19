from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    records = [
        json.loads(line)
        for line in args.source.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    selected = []
    for record in records:
        attack_type = record.get("attack_type")
        case_id = str(record.get("case_id", ""))
        if attack_type == "parameter_tampering":
            selected.append(record)
        elif attack_type == "confirmation_bypass" and not case_id.endswith(
            ("-010", "-011", "-012")
        ):
            selected.append(record)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in selected),
        encoding="utf-8",
    )
    print(f"generated {len(selected)} scenarios: {args.output}")


if __name__ == "__main__":
    main()
