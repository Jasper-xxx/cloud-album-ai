from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .metrics import EvaluationError


METRIC_LABELS = {
    "case_count": "Agent 用例数",
    "recall_at_5": "Recall@5",
    "recall_at_10": "Recall@10",
    "latency_p95_ms": "检索 P95",
    "pairwise_f1": "Pairwise F1",
    "wrong_merge_rate": "错误合并率",
    "manual_correction_rate": "人工修正率",
    "tool_selection_accuracy": "工具选择准确率",
    "parameter_extraction_accuracy": "参数抽取准确率",
    "parameter_case_exact_match_rate": "参数整例精确匹配率",
    "task_completion_rate": "任务完成率",
    "verification_coverage": "业务验证覆盖率",
    "agent_latency_p95_ms": "Agent 端到端 P95",
    "average_tokens_per_case": "平均 Token/用例",
    "interception_rate": "安全拦截率",
    "attack_case_count": "安全场景数",
    "skipped_case_count": "跳过场景数",
    "side_effect_verification_rate": "副作用验证通过率",
    "case_count_by_attack_type.unauthorized_access": "越权访问场景数",
    "case_count_by_attack_type.confirmation_bypass": "绕过确认场景数",
    "case_count_by_attack_type.parameter_tampering": "参数篡改场景数",
    "interception_rate_by_attack_type.unauthorized_access": "越权访问拦截率",
    "interception_rate_by_attack_type.confirmation_bypass": "绕过确认拦截率",
    "interception_rate_by_attack_type.parameter_tampering": "参数篡改拦截率",
    "eventual_success_rate": "最终成功率",
    "duplicate_execution_count": "额外执行次数",
    "duplicate_business_effect_count": "重复业务副作用数",
    "recovery_time_p95_ms": "恢复耗时 P95",
    "fault_case_count": "故障注入用例数",
    "cases_with_duplicate_execution": "发生重执行的用例数",
    "business_effect_observation_coverage": "业务副作用验证覆盖率",
}

SUITE_LABELS = {
    "image_search": "以图搜图",
    "face_clustering": "人脸聚类",
    "agent": "Agent",
    "security": "安全测试",
    "async_tasks": "异步任务",
}

SUITE_PRIMARY_METRICS = {
    "image_search": ["recall_at_5", "recall_at_10", "latency_p95_ms"],
    "face_clustering": ["pairwise_f1", "wrong_merge_rate", "manual_correction_rate"],
    "agent": [
        "tool_selection_accuracy",
        "parameter_extraction_accuracy",
        "task_completion_rate",
        "agent_latency_p95_ms",
    ],
    "security": ["interception_rate"],
    "async_tasks": [
        "eventual_success_rate",
        "duplicate_execution_count",
        "duplicate_business_effect_count",
        "recovery_time_p95_ms",
    ],
}


def _metric_value(metrics: dict[str, Any], path: str) -> Any:
    value: Any = metrics
    for segment in path.split("."):
        if not isinstance(value, dict) or segment not in value:
            return None
        value = value[segment]
    return value


def apply_gates(metrics: dict[str, Any], thresholds: dict[str, Any]) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    operators = {
        ">=": lambda actual, expected: actual >= expected,
        ">": lambda actual, expected: actual > expected,
        "<=": lambda actual, expected: actual <= expected,
        "<": lambda actual, expected: actual < expected,
        "==": lambda actual, expected: actual == expected,
    }
    for metric_path, gate in thresholds.items():
        if isinstance(gate, (int, float)):
            gate = {"op": ">=", "value": gate}
        if not isinstance(gate, dict):
            raise EvaluationError(f"invalid threshold for {metric_path}")
        op = str(gate.get("op", ">="))
        if op not in operators or "value" not in gate:
            raise EvaluationError(f"invalid gate for {metric_path}")
        expected = gate["value"]
        actual = _metric_value(metrics, metric_path)
        passed = actual is not None and operators[op](actual, expected)
        results.append(
            {
                "metric": metric_path,
                "actual": actual,
                "op": op,
                "expected": expected,
                "passed": passed,
            }
        )
    return results


def build_report(
    manifest: dict[str, Any], suite_results: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    sample_data = manifest.get("sample_data", False)
    if type(sample_data) is not bool:
        raise EvaluationError("manifest.sample_data must be a boolean")
    suites: dict[str, Any] = {}
    for name, metrics in suite_results.items():
        config = manifest["suites"][name]
        gates = apply_gates(metrics, config.get("thresholds") or {})
        suites[name] = {
            "metrics": metrics,
            "gates": gates,
            "passed": bool(gates) and all(gate["passed"] for gate in gates),
        }
    return {
        "project": manifest.get("project", "Cloud-Album"),
        "run_name": manifest.get("run_name", "evaluation"),
        "sample_data": sample_data,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "passed": bool(suites) and all(suite["passed"] for suite in suites.values()),
        "suites": suites,
    }


def _display_value(metric: str, value: Any) -> str:
    if value is None:
        return "N/A"
    if metric.endswith("_ms") and isinstance(value, (int, float)):
        return f"{float(value):.1f} ms"
    if isinstance(value, float):
        return f"{value:.2%}"
    return str(value)


def render_markdown(report: dict[str, Any]) -> str:
    title = f"# {report['project']} 测评报告"
    data_notice = (
        "> ⚠️ 本报告由示例数据生成，只验证评测链路，不代表真实系统效果。"
        if report["sample_data"]
        else "> 本报告由真实测评记录生成。"
    )
    lines = [
        title,
        "",
        data_notice,
        "",
        f"- 运行名称：`{report['run_name']}`",
        f"- 总体结论：**{'PASS' if report['passed'] else 'FAIL'}**",
        f"- 生成时间：`{report['generated_at']}`",
        "",
    ]
    for suite_name, suite in report["suites"].items():
        lines.extend(
            [
                f"## {SUITE_LABELS.get(suite_name, suite_name)} — {'PASS' if suite['passed'] else 'FAIL'}",
                "",
                "| 指标 | 实际值 | 门槛 | 结论 |",
                "|---|---:|---:|:---:|",
            ]
        )
        gates_by_metric = {gate["metric"]: gate for gate in suite["gates"]}
        display_metrics = list(SUITE_PRIMARY_METRICS.get(suite_name, []))
        display_metrics.extend(
            metric for metric in gates_by_metric if metric not in display_metrics
        )
        for metric in display_metrics:
            gate = gates_by_metric.get(metric)
            label = METRIC_LABELS.get(metric, metric)
            actual = gate["actual"] if gate else _metric_value(suite["metrics"], metric)
            threshold = (
                f"{gate['op']} {_display_value(metric, gate['expected'])}" if gate else "—"
            )
            conclusion = "✅" if gate and gate["passed"] else "❌" if gate else "—"
            lines.append(
                f"| {label} | {_display_value(metric, actual)} | {threshold} | {conclusion} |"
            )
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def write_markdown(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_markdown(report), encoding="utf-8")
