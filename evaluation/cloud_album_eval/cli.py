from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from .collectors import collect_async_tasks, collect_image_search, collect_security
from .io import (
    load_manifest,
    load_suite_records,
    read_records,
    write_json,
    write_jsonl,
)
from .metrics import EvaluationError, evaluate_suite
from .report import build_report, write_markdown
from .validation import validate_security_scenarios


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be >= 1")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Cloud-Album unified evaluation toolkit")
    subparsers = parser.add_subparsers(dest="command", required=True)

    evaluate = subparsers.add_parser("evaluate", help="evaluate one manifest")
    evaluate.add_argument("--manifest", type=Path, required=True)
    evaluate.add_argument("--output-dir", type=Path, required=True)

    image = subparsers.add_parser(
        "collect-image-search", help="call /imageSearch/search and create observations"
    )
    image.add_argument("--dataset", type=Path, required=True)
    image.add_argument("--base-url", default="http://127.0.0.1:8088")
    image.add_argument("--token-env", default="EVAL_USER_TOKEN")
    image.add_argument("--output", type=Path, required=True)
    image.add_argument("--timeout-seconds", type=float, default=90)
    image.add_argument(
        "--delay-ms",
        type=int,
        default=5100,
        help="default respects the endpoint's 0.2 requests/second rate limit",
    )
    image.add_argument("--repeats", type=_positive_int, default=1)

    security = subparsers.add_parser(
        "collect-security", help="run declarative HTTP security scenarios"
    )
    security.add_argument("--scenarios", type=Path, required=True)
    security.add_argument("--base-url", default="http://127.0.0.1:8088")
    security.add_argument("--output", type=Path, required=True)
    security.add_argument("--timeout-seconds", type=float, default=30)
    security.add_argument("--allow-mutating", action="store_true")

    async_tasks = subparsers.add_parser(
        "collect-async-tasks", help="poll fault-injected async tasks to terminal state"
    )
    async_tasks.add_argument("--cases", type=Path, required=True)
    async_tasks.add_argument("--base-url", default="http://127.0.0.1:8088")
    async_tasks.add_argument("--token-env", default="EVAL_USER_TOKEN")
    async_tasks.add_argument("--output", type=Path, required=True)
    async_tasks.add_argument("--timeout-seconds", type=float, default=900)
    async_tasks.add_argument("--poll-interval-seconds", type=float, default=2)
    return parser


def _evaluate(args: argparse.Namespace) -> int:
    manifest_path = args.manifest.resolve()
    manifest = load_manifest(manifest_path)
    suite_results = {}
    for suite_name, suite_config in manifest["suites"].items():
        if not isinstance(suite_config, dict) or not suite_config.get("enabled", True):
            continue
        records = load_suite_records(manifest_path, suite_config)
        suite_results[suite_name] = evaluate_suite(suite_name, records)
    report = build_report(manifest, suite_results)
    output_dir = args.output_dir.resolve()
    write_json(output_dir / "report.json", report)
    write_markdown(output_dir / "report.md", report)
    print(f"report: {output_dir / 'report.md'}")
    print(f"result: {'PASS' if report['passed'] else 'FAIL'}")
    return 0 if report["passed"] else 2


def _collect_image(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise EvaluationError(f"missing token environment variable: {args.token_env}")
    dataset_path = args.dataset.resolve()
    records = collect_image_search(
        read_records(dataset_path),
        dataset_path,
        args.base_url,
        token,
        args.timeout_seconds,
        args.delay_ms,
        args.repeats,
    )
    write_jsonl(args.output.resolve(), records)
    print(f"observations: {args.output.resolve()}")
    return 0


def _collect_security(args: argparse.Namespace) -> int:
    scenarios = read_records(args.scenarios.resolve())
    validate_security_scenarios(scenarios)
    records = collect_security(
        scenarios,
        args.base_url,
        args.timeout_seconds,
        args.allow_mutating,
    )
    write_jsonl(args.output.resolve(), records)
    print(f"observations: {args.output.resolve()}")
    return 0


def _collect_async(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise EvaluationError(f"missing token environment variable: {args.token_env}")
    records = collect_async_tasks(
        read_records(args.cases.resolve()),
        args.base_url,
        token,
        args.timeout_seconds,
        args.poll_interval_seconds,
    )
    write_jsonl(args.output.resolve(), records)
    print(f"observations: {args.output.resolve()}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "evaluate":
            return _evaluate(args)
        if args.command == "collect-image-search":
            return _collect_image(args)
        if args.command == "collect-security":
            return _collect_security(args)
        if args.command == "collect-async-tasks":
            return _collect_async(args)
    except (EvaluationError, OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 1
