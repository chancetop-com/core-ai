"""Summarize a Harbor job into a Markdown report and a CSV file."""

from __future__ import annotations

import argparse
import csv
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


@dataclass
class TrialRow:
    task: str
    trial: str
    reward: float | None
    state: str
    duration_sec: float | None
    agent_sec: float | None
    input_tokens: int | None
    output_tokens: int | None
    cache_tokens: int | None
    error: str


def _timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _duration(start: str | None, end: str | None) -> float | None:
    started = _timestamp(start)
    finished = _timestamp(end)
    if not started or not finished:
        return None
    return round((finished - started).total_seconds(), 2)


def _error_text(exception_info: object) -> str:
    if exception_info is None:
        return ""
    if isinstance(exception_info, dict):
        for key in ("message", "exception_message", "error", "type"):
            value = exception_info.get(key)
            if value:
                return str(value).replace("\n", " ")[:300]
        return json.dumps(exception_info, ensure_ascii=False)[:300]
    return str(exception_info).replace("\n", " ")[:300]


def _has_trial_results(path: Path) -> bool:
    for child in path.iterdir():
        result_path = child / "result.json"
        if not child.is_dir() or not result_path.is_file():
            continue
        try:
            payload = json.loads(result_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(payload, dict) and "task_name" in payload:
            return True
    return False


def _latest_job_dir(path: Path) -> Path:
    if _has_trial_results(path):
        return path
    candidates = [
        child for child in path.iterdir() if child.is_dir() and _has_trial_results(child)
    ]
    if not candidates:
        raise FileNotFoundError(f"No Harbor job directory found under {path}")
    return max(candidates, key=lambda child: child.stat().st_mtime)


def _load_trials(job_dir: Path) -> list[TrialRow]:
    rows: list[TrialRow] = []
    for result_path in sorted(job_dir.glob("*/result.json")):
        try:
            payload = json.loads(result_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(payload, dict) or "task_name" not in payload:
            continue

        task = str(payload.get("task_name") or result_path.parent.name)
        if task.startswith("terminal-bench/"):
            task = task.removeprefix("terminal-bench/")
        reward = None
        verifier = payload.get("verifier_result") or {}
        rewards = verifier.get("rewards") if isinstance(verifier, dict) else None
        if isinstance(rewards, dict):
            value = rewards.get("reward")
            if isinstance(value, (int, float)):
                reward = float(value)

        error = _error_text(payload.get("exception_info"))
        if error:
            state = "error"
        elif reward is None:
            state = "unknown"
        elif reward >= 1.0:
            state = "pass"
        else:
            state = "fail"

        agent_result = payload.get("agent_result") or {}
        agent_execution = payload.get("agent_execution") or {}
        rows.append(
            TrialRow(
                task=task,
                trial=str(payload.get("trial_name") or result_path.parent.name),
                reward=reward,
                state=state,
                duration_sec=_duration(
                    payload.get("started_at"), payload.get("finished_at")
                ),
                agent_sec=_duration(
                    agent_execution.get("started_at"),
                    agent_execution.get("finished_at"),
                ),
                input_tokens=agent_result.get("n_input_tokens"),
                output_tokens=agent_result.get("n_output_tokens"),
                cache_tokens=agent_result.get("n_cache_tokens"),
                error=error,
            )
        )
    return rows


def _agent_info(job_dir: Path) -> tuple[str, str]:
    for result_path in sorted(job_dir.glob("*/result.json")):
        try:
            payload = json.loads(result_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        config = payload.get("config") or {}
        agent = config.get("agent") or {}
        model = str(agent.get("model_name") or "unknown")
        name = str(agent.get("name") or "core-ai-cli")
        return name, model
    return "core-ai-cli", "unknown"


def _fmt(value: float | int | None, digits: int = 2) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def _write_csv(path: Path, rows: list[TrialRow]) -> None:
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(
            [
                "task",
                "trial",
                "state",
                "reward",
                "duration_sec",
                "agent_sec",
                "input_tokens",
                "output_tokens",
                "cache_tokens",
                "error",
            ]
        )
        for row in rows:
            writer.writerow(
                [
                    row.task,
                    row.trial,
                    row.state,
                    row.reward,
                    row.duration_sec,
                    row.agent_sec,
                    row.input_tokens,
                    row.output_tokens,
                    row.cache_tokens,
                    row.error,
                ]
            )


def _write_markdown(
    path: Path,
    job_dir: Path,
    rows: list[TrialRow],
    agent_name: str,
    model: str,
) -> None:
    passed = sum(1 for row in rows if row.state == "pass")
    failed = sum(1 for row in rows if row.state == "fail")
    unknown = sum(1 for row in rows if row.state == "unknown")
    errors = sum(1 for row in rows if row.state == "error")
    scored = passed + failed
    scored_rate = (passed / scored * 100.0) if scored else 0.0
    total_rate = (passed / len(rows) * 100.0) if rows else 0.0
    rewarded = [row.reward for row in rows if row.reward is not None]
    mean_reward = (sum(rewarded) / len(rewarded)) if rewarded else 0.0
    input_tokens = sum(row.input_tokens or 0 for row in rows)
    output_tokens = sum(row.output_tokens or 0 for row in rows)
    cache_tokens = sum(row.cache_tokens or 0 for row in rows)

    lines = [
        "# Terminal-Bench 2.1 Report",
        "",
        f"- Job: `{job_dir.name}`",
        f"- Agent: `{agent_name}`",
        f"- Model: `{model}`",
        f"- Trials: {len(rows)}",
        f"- Passed: {passed}",
        f"- Failed: {failed}",
        f"- Unknown: {unknown}",
        f"- Infra errors: {errors}",
        f"- Pass rate (scored): {scored_rate:.1f}% ({passed}/{scored})",
        f"- Pass rate (all trials): {total_rate:.1f}% ({passed}/{len(rows)})",
        f"- Mean reward: {mean_reward:.3f}",
        f"- Tokens: input {input_tokens:,}, output {output_tokens:,}, cache {cache_tokens:,}",
        "",
        "## Tasks",
        "",
        "| Task | State | Reward | Duration | Agent | Input | Output | Cache |",
        "|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for row in sorted(rows, key=lambda item: (item.state != "pass", item.task)):
        lines.append(
            "| {task} | {state} | {reward} | {duration}s | {agent}s | "
            "{input_tokens} | {output_tokens} | {cache_tokens} |".format(
                task=row.task,
                state=row.state,
                reward=_fmt(row.reward, 3),
                duration=_fmt(row.duration_sec, 1),
                agent=_fmt(row.agent_sec, 1),
                input_tokens=_fmt(row.input_tokens),
                output_tokens=_fmt(row.output_tokens),
                cache_tokens=_fmt(row.cache_tokens),
            )
        )

    error_rows = [row for row in rows if row.state == "error"]
    if error_rows:
        lines.extend(["", "## Errors", ""])
        for row in error_rows:
            lines.append(f"- `{row.task}`: {row.error}")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Summarize a Harbor job into report.md and summary.csv"
    )
    parser.add_argument(
        "path",
        type=Path,
        help="Harbor job directory, or a parent containing job directories",
    )
    args = parser.parse_args()

    job_dir = _latest_job_dir(args.path)
    rows = _load_trials(job_dir)
    agent_name, model = _agent_info(job_dir)
    report_path = job_dir / "report.md"
    csv_path = job_dir / "summary.csv"
    _write_csv(csv_path, rows)
    _write_markdown(report_path, job_dir, rows, agent_name, model)

    passed = sum(1 for row in rows if row.state == "pass")
    errors = sum(1 for row in rows if row.state == "error")
    print(f"Report: {report_path}")
    print(f"CSV:    {csv_path}")
    print(f"Passed: {passed}/{len(rows)} (infra errors: {errors})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
