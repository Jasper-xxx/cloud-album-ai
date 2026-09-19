from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from .collectors import (
    collect_async_tasks,
    build_dify_agent_predictions,
    collect_dify_agent,
    collect_dify_agent_runs,
    collect_image_search,
    collect_security,
    prepare_ai_outage_tasks,
)
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

    agent = subparsers.add_parser(
        "collect-agent", help="run real Dify WebApp cases and collect node traces"
    )
    agent.add_argument("--dataset", type=Path, required=True)
    agent.add_argument("--webapp-url", required=True)
    agent.add_argument("--app-code", required=True)
    agent.add_argument("--output", type=Path, required=True)
    agent.add_argument("--timeout-seconds", type=float, default=90)
    agent.add_argument("--delay-ms", type=int, default=0)
    agent.add_argument("--trace-container", default="docker-db_postgres-1")
    agent.add_argument("--trace-database", default="dify")
    agent.add_argument("--trace-database-user", default="postgres")
    agent.add_argument("--api-key-env")
    agent.add_argument("--service-api-base", default="http://localhost")
    agent.add_argument("--user", default="cloud-album-eval")

    agent_runs = subparsers.add_parser(
        "collect-agent-runs", help="run Dify WebApp cases without Docker trace access"
    )
    agent_runs.add_argument("--dataset", type=Path, required=True)
    agent_runs.add_argument("--webapp-url", required=True)
    agent_runs.add_argument("--app-code", required=True)
    agent_runs.add_argument("--output", type=Path, required=True)
    agent_runs.add_argument("--timeout-seconds", type=float, default=90)
    agent_runs.add_argument("--delay-ms", type=int, default=0)
    agent_runs.add_argument("--api-key-env")
    agent_runs.add_argument("--service-api-base", default="http://localhost")
    agent_runs.add_argument("--user", default="cloud-album-eval")

    agent_traces = subparsers.add_parser(
        "collect-agent-traces", help="join saved Dify runs with PostgreSQL node traces"
    )
    agent_traces.add_argument("--dataset", type=Path, required=True)
    agent_traces.add_argument("--runs", type=Path, required=True)
    agent_traces.add_argument("--output", type=Path, required=True)
    agent_traces.add_argument("--trace-container", default="docker-db_postgres-1")
    agent_traces.add_argument("--trace-database", default="dify")
    agent_traces.add_argument("--trace-database-user", default="postgres")

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

    ai_outage = subparsers.add_parser(
        "prepare-ai-outage",
        help="submit preview-only image-tag tasks while ai-service is offline",
    )
    ai_outage.add_argument("--file-id", action="append", required=True)
    ai_outage.add_argument("--batches", type=_positive_int, default=5)
    ai_outage.add_argument("--base-url", default="http://127.0.0.1:8088")
    ai_outage.add_argument("--ai-service-url", default="http://127.0.0.1:5000")
    ai_outage.add_argument("--token-env", default="EVAL_USER_TOKEN")
    ai_outage.add_argument("--output", type=Path, required=True)
    ai_outage.add_argument("--timeout-seconds", type=float, default=90)
    ai_outage.add_argument("--poll-interval-seconds", type=float, default=1)
    ai_outage.add_argument("--case-id-start", type=_positive_int, default=1)
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


def _collect_agent(args: argparse.Namespace) -> int:
    api_key = os.environ.get(args.api_key_env) if args.api_key_env else None
    if args.api_key_env and not api_key:
        raise EvaluationError(f"missing API key environment variable: {args.api_key_env}")
    records = collect_dify_agent(
        read_records(args.dataset.resolve()),
        args.webapp_url,
        args.app_code,
        args.timeout_seconds,
        args.delay_ms,
        args.trace_container,
        args.trace_database,
        args.trace_database_user,
        api_key=api_key,
        service_api_base=args.service_api_base,
        user=args.user,
    )
    write_jsonl(args.output.resolve(), records)
    print(f"predictions: {args.output.resolve()}")
    return 0


def _collect_agent_runs(args: argparse.Namespace) -> int:
    api_key = os.environ.get(args.api_key_env) if args.api_key_env else None
    if args.api_key_env and not api_key:
        raise EvaluationError(f"missing API key environment variable: {args.api_key_env}")
    records = collect_dify_agent_runs(
        read_records(args.dataset.resolve()),
        args.webapp_url,
        args.app_code,
        args.timeout_seconds,
        args.delay_ms,
        api_key=api_key,
        service_api_base=args.service_api_base,
        user=args.user,
    )
    write_jsonl(args.output.resolve(), records)
    print(f"runs: {args.output.resolve()}")
    return 0


def _collect_agent_traces(args: argparse.Namespace) -> int:
    records = build_dify_agent_predictions(
        read_records(args.dataset.resolve()),
        read_records(args.runs.resolve()),
        args.trace_container,
        args.trace_database,
        args.trace_database_user,
    )
    write_jsonl(args.output.resolve(), records)
    print(f"predictions: {args.output.resolve()}")
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


def _prepare_ai_outage(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise EvaluationError(f"missing token environment variable: {args.token_env}")
    records = prepare_ai_outage_tasks(
        args.file_id,
        args.batches,
        args.base_url,
        args.ai_service_url,
        token,
        args.timeout_seconds,
        args.poll_interval_seconds,
        args.case_id_start,
    )
    write_jsonl(args.output.resolve(), records)
    print(f"fault cases: {args.output.resolve()}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "evaluate":
            return _evaluate(args)
        if args.command == "collect-image-search":
            return _collect_image(args)
        if args.command == "collect-agent":
            return _collect_agent(args)
        if args.command == "collect-agent-runs":
            return _collect_agent_runs(args)
        if args.command == "collect-agent-traces":
            return _collect_agent_traces(args)
        if args.command == "collect-security":
            return _collect_security(args)
        if args.command == "collect-async-tasks":
            return _collect_async(args)
        if args.command == "prepare-ai-outage":
            return _prepare_ai_outage(args)
    except (EvaluationError, OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 1
