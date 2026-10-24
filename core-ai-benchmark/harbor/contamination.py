"""Flag trials that reached outside the task container for help.

Terminal-Bench tasks are published in public repositories, so an agent that can
reach the network can read the task's own tests, reference solution, or the
published leaderboard expectations. That turns a score into a lookup exercise.
This script scans the agent transcripts of a finished Harbor job and separates
clean trials from contaminated ones.

Verdicts, from most to least severe:

* ``answer_key`` - a tool call referenced this task's own path inside a
  benchmark repository (``.../tasks/<task>/...``), i.e. its tests or solution.
* ``benchmark_repo`` - a tool call referenced a benchmark repository without
  targeting this task's own path.
* ``web_external`` - the agent used the ``web_search`` / ``web_fetch`` tools.
* ``clean`` - none of the above.

Usage:
    python contamination.py <job-dir>
    python contamination.py <job-dir> --strict
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
TOOL_CALL = re.compile(r"^\s*●\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(")

WEB_TOOLS = {"WebSearch", "WebFetch", "WebQuery"}

REPO_MARKERS = (
    "terminal-bench",
    "frontier-bench",
    "harborframework.com",
    "raw.githubusercontent.com",
    "api.github.com",
    "marginlab.ai",
)

# Hosts that exist only to relay another URL; reported, but not a verdict on
# their own because the relayed URL decides what was fetched.
RELAY_MARKERS = ("r.jina.ai",)

OWN_TASK_PATH = re.compile(r"tasks[/\\]([a-z0-9_.-]+)")

CLEAN = "clean"
WEB_EXTERNAL = "web_external"
BENCHMARK_REPO = "benchmark_repo"
ANSWER_KEY = "answer_key"

SEVERITY = {CLEAN: 0, WEB_EXTERNAL: 1, BENCHMARK_REPO: 2, ANSWER_KEY: 3}


@dataclass
class TrialReport:
    task: str
    trial: str
    reward: float | None
    verdict: str = CLEAN
    web_calls: int = 0
    repo_calls: int = 0
    own_task_calls: int = 0
    relay_calls: int = 0
    evidence: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.reward is not None and self.reward >= 1.0


def _reward(trial_dir: Path) -> float | None:
    reward_path = trial_dir / "verifier" / "reward.txt"
    try:
        text = reward_path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        text = ""
    if text:
        try:
            return float(text.split()[0])
        except (ValueError, IndexError):
            pass

    result_path = trial_dir / "result.json"
    try:
        payload = json.loads(result_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    verifier = payload.get("verifier_result") or {}
    rewards = verifier.get("rewards") if isinstance(verifier, dict) else None
    if isinstance(rewards, dict):
        value = rewards.get("reward")
        if isinstance(value, (int, float)):
            return float(value)
    return None


def _task_name(trial_dir: Path) -> str:
    result_path = trial_dir / "result.json"
    try:
        payload = json.loads(result_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        payload = {}
    name = str(payload.get("task_name") or "")
    if name.startswith("terminal-bench/"):
        name = name.removeprefix("terminal-bench/")
    if name:
        return name
    return trial_dir.name.split("__", 1)[0]


def _is_benchmark_reference(line: str) -> bool:
    lowered = line.lower()
    return any(marker in lowered for marker in REPO_MARKERS)


def _is_relay(line: str) -> bool:
    lowered = line.lower()
    return any(marker in lowered for marker in RELAY_MARKERS)


def _mentions_own_task(line: str, task: str) -> bool:
    if not task:
        return False
    lowered = line.lower()
    if task.lower() in lowered:
        return True
    # Transcripts truncate long arguments, so a path can be cut mid-name
    # (".../tasks/bui..."); treat a path segment that prefixes the task name as
    # this task's own directory.
    for match in OWN_TASK_PATH.finditer(lowered):
        segment = match.group(1).rstrip(".")
        if len(segment) >= 3 and task.lower().startswith(segment):
            return True
    return False


def scan_transcript(path: Path, task: str) -> TrialReport:
    report = TrialReport(task=task, trial=path.parent.parent.name, reward=None)
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return report

    for raw in text.splitlines():
        line = ANSI.sub("", raw)
        match = TOOL_CALL.match(line)
        if not match:
            continue
        tool = match.group(1)
        if tool == "Thinking":
            continue
        is_repo = _is_benchmark_reference(line)
        is_own = is_repo and _mentions_own_task(line, task)
        is_relay = _is_relay(line)

        if tool in WEB_TOOLS:
            report.web_calls += 1
        if is_repo:
            report.repo_calls += 1
        if is_own:
            report.own_task_calls += 1
        if is_relay:
            report.relay_calls += 1

        if is_own:
            report.verdict = ANSWER_KEY
        elif is_repo and report.verdict != ANSWER_KEY:
            report.verdict = BENCHMARK_REPO
        elif (tool in WEB_TOOLS or is_relay) and report.verdict == CLEAN:
            report.verdict = WEB_EXTERNAL

        if (is_own or is_repo or is_relay) and len(report.evidence) < 3:
            report.evidence.append(_shorten(line))
    return report


def _shorten(line: str, limit: int = 160) -> str:
    text = " ".join(line.split())
    return text if len(text) <= limit else text[: limit - 3] + "..."


def _load_job(job_dir: Path) -> list[TrialReport]:
    reports: list[TrialReport] = []
    for trial_dir in sorted(child for child in job_dir.iterdir() if child.is_dir()):
        transcript = trial_dir / "agent" / "core-ai-cli.txt"
        if not transcript.is_file():
            transcript = trial_dir / "agent" / "core-ai-cli.clean.txt"
        if not transcript.is_file():
            continue
        report = scan_transcript(transcript, _task_name(trial_dir))
        report.reward = _reward(trial_dir)
        reports.append(report)
    return reports


def _latest_job_dir(path: Path) -> Path:
    if _load_job(path):
        return path
    candidates = [
        child
        for child in path.iterdir()
        if child.is_dir() and (child / "job.log").is_file() and _load_job(child)
    ]
    if not candidates:
        raise FileNotFoundError(f"No Harbor job directory found under {path}")
    return max(candidates, key=lambda child: child.stat().st_mtime)


def _disabled_tools(job_dir: Path) -> str:
    log = job_dir / "job.log"
    try:
        text = log.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""
    match = re.search(r"agent\.tools\.disabled=([^\s\\']+)", text)
    return match.group(1) if match else ""


def _score(reports: list[TrialReport]) -> dict[str, float]:
    total = len(reports)
    passed = sum(1 for item in reports if item.passed)
    clean = [item for item in reports if item.verdict == CLEAN]
    answer_key = [item for item in reports if item.verdict == ANSWER_KEY]
    clean_passed = sum(1 for item in clean if item.passed)
    return {
        "total": total,
        "passed": passed,
        "rate": (passed / total * 100.0) if total else 0.0,
        "clean_total": len(clean),
        "clean_passed": clean_passed,
        "clean_rate": (clean_passed / len(clean) * 100.0) if clean else 0.0,
        "answer_key_total": len(answer_key),
        "answer_key_passed": sum(1 for item in answer_key if item.passed),
    }


def _write_csv(path: Path, reports: list[TrialReport]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "task",
                "trial",
                "reward",
                "verdict",
                "web_calls",
                "repo_calls",
                "own_task_calls",
                "relay_calls",
                "evidence",
            ]
        )
        for report in sorted(
            reports, key=lambda item: (-SEVERITY[item.verdict], item.task)
        ):
            writer.writerow(
                [
                    report.task,
                    report.trial,
                    "" if report.reward is None else report.reward,
                    report.verdict,
                    report.web_calls,
                    report.repo_calls,
                    report.own_task_calls,
                    report.relay_calls,
                    " | ".join(report.evidence),
                ]
            )


def _write_markdown(
    path: Path, job_dir: Path, reports: list[TrialReport], disabled: str
) -> None:
    stats = _score(reports)
    lines = [
        "# Contamination report",
        "",
        f"- Job: `{job_dir.name}`",
        f"- Trials: {stats['total']:.0f}",
        f"- Disabled tools configured by the harness: `{disabled or 'none recorded'}`",
        "",
        "## Scores",
        "",
        "| Metric | Value |",
        "|---|---:|",
        f"| Raw pass rate | {stats['rate']:.1f}% "
        f"({stats['passed']:.0f}/{stats['total']:.0f}) |",
        f"| Clean-trial pass rate | {stats['clean_rate']:.1f}% "
        f"({stats['clean_passed']:.0f}/{stats['clean_total']:.0f}) |",
        f"| Passes on contaminated trials | "
        f"{stats['passed'] - stats['clean_passed']:.0f}/"
        f"{stats['total'] - stats['clean_total']:.0f} |",
        f"| Trials reading their own answer key | "
        f"{stats['answer_key_passed']:.0f}/{stats['answer_key_total']:.0f} passed |",
        "",
        "The clean rate counts only trials that used no web tool and touched no "
        "benchmark repository; contaminated trials are excluded rather than "
        "scored. The configured tool list comes from `job.log` and shows intent, "
        "not enforcement - a CLI older than 2.0.61 ignores the property.",
        "",
        "## Contaminated trials",
        "",
        "| Task | Verdict | Reward | Web | Repo | Own task | Relay |",
        "|---|---|---:|---:|---:|---:|---:|",
    ]

    flagged = [
        report
        for report in sorted(
            reports, key=lambda item: (-SEVERITY[item.verdict], item.task)
        )
        if report.verdict != CLEAN
    ]
    for report in flagged:
        reward = "-" if report.reward is None else f"{report.reward:.3f}"
        lines.append(
            f"| {report.task} | {report.verdict} | {reward} | {report.web_calls} | "
            f"{report.repo_calls} | {report.own_task_calls} | {report.relay_calls} |"
        )
    if not flagged:
        lines.append("| - | none | - | - | - | - | - |")

    evidence_rows = [report for report in flagged if report.evidence]
    if evidence_rows:
        lines.extend(["", "## Evidence", ""])
        for report in evidence_rows:
            lines.append(f"### {report.task} ({report.verdict})")
            lines.append("")
            for item in report.evidence:
                lines.append(f"- `{item}`")
            lines.append("")

    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Flag trials that used the network to read benchmark answers"
    )
    parser.add_argument("path", type=Path, help="Harbor job directory, or a parent containing job directories")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Exit with status 2 when a trial read its own answer key",
    )
    args = parser.parse_args()

    job_dir = args.path
    if not job_dir.is_dir():
        print(f"job directory not found: {job_dir}")
        return 1

    try:
        job_dir = _latest_job_dir(job_dir)
    except FileNotFoundError as error:
        print(error)
        return 1

    reports = _load_job(job_dir)
    if not reports:
        print(f"no agent transcripts found under {job_dir}")
        return 1

    stats = _score(reports)
    csv_path = job_dir / "contamination.csv"
    md_path = job_dir / "contamination.md"
    _write_csv(csv_path, reports)
    _write_markdown(md_path, job_dir, reports, _disabled_tools(job_dir))

    by_verdict = {verdict: 0 for verdict in SEVERITY}
    for report in reports:
        by_verdict[report.verdict] += 1

    print(f"Report: {md_path}")
    print(f"CSV:    {csv_path}")
    print(
        f"Trials: {stats['total']:.0f} | raw pass {stats['passed']:.0f} "
        f"({stats['rate']:.1f}%) | clean pass {stats['clean_passed']:.0f}/"
        f"{stats['clean_total']:.0f} ({stats['clean_rate']:.1f}%)"
    )
    print(
        "Verdicts: "
        f"answer_key {by_verdict[ANSWER_KEY]}, "
        f"benchmark_repo {by_verdict[BENCHMARK_REPO]}, "
        f"web_external {by_verdict[WEB_EXTERNAL]}, "
        f"clean {by_verdict[CLEAN]}"
    )

    if args.strict and by_verdict[ANSWER_KEY]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
