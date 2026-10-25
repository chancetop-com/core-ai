"""Compare two Harbor jobs task by task.

Useful when two harnesses run the same dataset: it shows which tasks flipped,
how long each side took, and how many tokens each spent.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


@dataclass
class Trial:
    task: str
    reward: float | None
    seconds: float | None
    input_tokens: int
    output_tokens: int


def _seconds(start: str | None, end: str | None) -> float | None:
    if not start or not end:
        return None
    try:
        fmt = lambda value: datetime.fromisoformat(value.replace("Z", "+00:00"))
        return (fmt(end) - fmt(start)).total_seconds()
    except ValueError:
        return None


def load_job(job_dir: Path) -> dict[str, Trial]:
    trials: dict[str, Trial] = {}
    for path in sorted(job_dir.glob("*/result.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        task = str(payload.get("task_name") or path.parent.name).removeprefix(
            "terminal-bench/"
        )
        verifier = payload.get("verifier_result") or {}
        rewards = verifier.get("rewards") if isinstance(verifier, dict) else None
        reward = rewards.get("reward") if isinstance(rewards, dict) else None
        execution = payload.get("agent_execution") or {}
        agent = payload.get("agent_result") or {}
        trials[task] = Trial(
            task=task,
            reward=float(reward) if isinstance(reward, (int, float)) else None,
            seconds=_seconds(execution.get("started_at"), execution.get("finished_at")),
            input_tokens=int(agent.get("n_input_tokens") or 0),
            output_tokens=int(agent.get("n_output_tokens") or 0),
        )
    return trials


def _mark(trial: Trial | None) -> str:
    if trial is None or trial.reward is None:
        return "-"
    return "PASS" if trial.reward >= 1.0 else "fail"


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare two Harbor jobs")
    parser.add_argument("job_a", type=Path)
    parser.add_argument("job_b", type=Path)
    parser.add_argument("--label-a", default="A")
    parser.add_argument("--label-b", default="B")
    args = parser.parse_args()

    a = load_job(args.job_a)
    b = load_job(args.job_b)
    tasks = sorted(set(a) | set(b))

    print(
        f"{'task':34} {args.label_a:>5} {args.label_b:>5} "
        f"{'sec A':>7} {'sec B':>7} {'tok A':>9} {'tok B':>9}"
    )
    only_a: list[str] = []
    only_b: list[str] = []
    both: list[str] = []
    neither: list[str] = []
    for task in tasks:
        ta, tb = a.get(task), b.get(task)
        ma, mb = _mark(ta), _mark(tb)
        if ma == "PASS" and mb == "PASS":
            both.append(task)
        elif ma == "PASS":
            only_a.append(task)
        elif mb == "PASS":
            only_b.append(task)
        else:
            neither.append(task)
        print(
            f"{task:34} {ma:>5} {mb:>5} "
            f"{int(ta.seconds) if ta and ta.seconds else '-':>7} "
            f"{int(tb.seconds) if tb and tb.seconds else '-':>7} "
            f"{(ta.input_tokens + ta.output_tokens) if ta else '-':>9} "
            f"{(tb.input_tokens + tb.output_tokens) if tb else '-':>9}"
        )

    def passed(trials: dict[str, Trial]) -> int:
        return sum(1 for trial in trials.values() if trial.reward and trial.reward >= 1)

    print()
    print(f"{args.label_a}: {passed(a)}/{len(a)}   {args.label_b}: {passed(b)}/{len(b)}")
    print(f"both pass ({len(both)}): {', '.join(both) or '-'}")
    print(f"only {args.label_a} ({len(only_a)}): {', '.join(only_a) or '-'}")
    print(f"only {args.label_b} ({len(only_b)}): {', '.join(only_b) or '-'}")
    print(f"neither ({len(neither)}): {', '.join(neither) or '-'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
