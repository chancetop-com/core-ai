"""Pin the Harbor network policy of a Terminal-Bench dataset checkout.

Harbor reads the per-task network policy from ``task.toml`` (``[environment]``
``network_mode`` / ``allowed_hosts``). Leaving it unset means ``public``: the
container can reach anything, including the public benchmark repositories that
hold each task's tests and reference solution. This script rewrites those two
fields for every task, optionally adding per-task hosts that the task
legitimately needs.

The original ``task.toml`` files are kept in a manifest so the change can be
reverted. Run ``--revert`` after a run to restore the upstream dataset.

Examples:
    python network_policy.py --mode allowlist
    python network_policy.py --mode no-network
    python network_policy.py --mode allowlist --dry-run
    python network_policy.py --revert

Harbor's ``allowlist`` mode is enforced by an egress sidecar that proxies TCP
and matches SNI hostnames; the LLM endpoint of the agent has to be in the list,
and this script adds it automatically from ``--api-base``, ``LITELLM_API_BASE``
or the active entry in ``~/.core-ai/auth.json``.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

try:
    import tomllib
except ModuleNotFoundError:
    tomllib = None

MODES = ("allowlist", "no-network", "public")
NETWORK_KEYS = ("network_mode", "allowed_hosts")

DEFAULT_DATASET = Path(__file__).resolve().parent / ".datasets" / "terminal-bench-2-1"
DEFAULT_HOSTS = Path(__file__).resolve().parent / "network_allowlist.txt"
DEFAULT_TASK_HOSTS = Path(__file__).resolve().parent / "network_hosts.json"
MANIFEST_NAME = ".network-policy.json"


def load_hosts(path: Path) -> list[str]:
    if not path.is_file():
        return []
    hosts: list[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line and line not in hosts:
            hosts.append(line)
    return hosts


def load_task_hosts(path: Path) -> dict[str, list[str]]:
    if not path.is_file():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object of task -> hosts")
    return {
        str(task): [str(host) for host in hosts]
        for task, hosts in payload.items()
        if isinstance(hosts, list)
    }


def api_host(api_base: str | None) -> str | None:
    if not api_base:
        return None
    parsed = urlparse(api_base if "//" in api_base else f"https://{api_base}")
    return parsed.hostname or None


def resolve_api_host(explicit: str | None) -> str | None:
    host = api_host(explicit) or api_host(os.environ.get("LITELLM_API_BASE"))
    if host:
        return host
    auth_file = Path.home() / ".core-ai" / "auth.json"
    try:
        payload = json.loads(auth_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    entry = None
    if isinstance(payload, list):
        entry = next(
            (item for item in payload if isinstance(item, dict) and item.get("active")),
            None,
        )
    elif isinstance(payload, dict):
        entry = payload
    if not entry:
        return None
    return api_host(str(entry.get("server_url") or ""))


def _section_bounds(lines: list[str], name: str) -> tuple[int, int] | None:
    header = f"[{name}]"
    for index, line in enumerate(lines):
        if line.strip() == header:
            end = len(lines)
            for offset in range(index + 1, len(lines)):
                if lines[offset].lstrip().startswith("["):
                    end = offset
                    break
            return index, end
    return None


def _strip_keys(lines: list[str], start: int, end: int) -> list[str]:
    kept: list[str] = []
    skip_array = False
    for index, line in enumerate(lines):
        if not (start < index < end):
            kept.append(line)
            continue
        if skip_array:
            if "]" in line:
                skip_array = False
            continue
        stripped = line.strip()
        key = stripped.split("=", 1)[0].strip() if "=" in stripped else ""
        if key in NETWORK_KEYS:
            if "[" in stripped and "]" not in stripped:
                skip_array = True
            continue
        kept.append(line)
    return kept


def render_policy(
    mode: str, hosts: list[str], *, explicit_public: bool = False
) -> list[str]:
    if mode == "same":
        return []
    if mode == "public" and not explicit_public:
        return []
    rendered = [f'network_mode = "{mode}"']
    if mode == "allowlist":
        body = ", ".join(json.dumps(host) for host in hosts)
        rendered.append(f"allowed_hosts = [{body}]")
    return rendered


def _apply_section(lines: list[str], name: str, rendered: list[str]) -> list[str]:
    bounds = _section_bounds(lines, name)
    if bounds is None:
        if not rendered:
            return lines
        if lines and lines[-1].strip():
            lines.append("")
        lines.append(f"[{name}]")
        lines.extend(rendered)
        return lines

    start, end = bounds
    lines = _strip_keys(lines, start, end)
    bounds = _section_bounds(lines, name)
    assert bounds is not None
    start, _ = bounds
    lines[start + 1 : start + 1] = rendered
    return lines


def patch_text(text: str, policies: dict[str, list[str]]) -> str:
    lines = text.splitlines()
    for name, rendered in policies.items():
        lines = _apply_section(lines, name, rendered)
    return "\n".join(lines) + "\n"


def validate(
    text: str, task: str, mode: str, hosts: list[str], verifier_mode: str
) -> None:
    if tomllib is None:
        return
    payload = tomllib.loads(text)
    environment = payload.get("environment") or {}
    actual_mode = environment.get("network_mode")
    actual_hosts = environment.get("allowed_hosts") or []
    expected_mode = None if mode == "public" else mode
    if actual_mode != expected_mode:
        raise ValueError(
            f"{task}: network_mode is {actual_mode!r}, expected {expected_mode!r}"
        )
    if expected_mode == "allowlist" and list(actual_hosts) != hosts:
        raise ValueError(f"{task}: allowed_hosts was not written as expected")
    if verifier_mode != "same" and mode != "public":
        verifier = payload.get("verifier") or {}
        if verifier.get("network_mode") != verifier_mode:
            raise ValueError(
                f"{task}: verifier network_mode is "
                f"{verifier.get('network_mode')!r}, expected {verifier_mode!r}"
            )


def _load_manifest(path: Path) -> dict:
    if not path.is_file():
        return {"tasks": {}}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"tasks": {}}
    if not isinstance(payload, dict) or not isinstance(payload.get("tasks"), dict):
        return {"tasks": {}}
    return payload


def apply_policy(
    dataset: Path,
    mode: str,
    verifier_mode: str,
    base_hosts: list[str],
    task_hosts: dict[str, list[str]],
    extra_hosts: list[str],
    dry_run: bool,
) -> int:
    tasks_dir = dataset / "tasks"
    if not tasks_dir.is_dir():
        print(f"dataset not found: {dataset}")
        return 1

    manifest_path = dataset / MANIFEST_NAME
    manifest = _load_manifest(manifest_path)
    backups: dict[str, str] = manifest.setdefault("tasks", {})

    patched = 0
    skipped: list[str] = []
    for task_dir in sorted(child for child in tasks_dir.iterdir() if child.is_dir()):
        task_file = task_dir / "task.toml"
        if not task_file.is_file():
            skipped.append(task_dir.name)
            continue
        task = task_dir.name
        original = task_file.read_text(encoding="utf-8")
        backups.setdefault(task, original)
        hosts = list(dict.fromkeys([*base_hosts, *task_hosts.get(task, []), *extra_hosts]))
        hosts = _drop_duplicates(hosts)
        policies = {"environment": render_policy(mode, hosts)}
        if mode != "public":
            policies["verifier"] = render_policy(
                verifier_mode, hosts, explicit_public=True
            )
        updated = patch_text(original, policies)
        validate(updated, task, mode, hosts, verifier_mode)
        patched += 1
        if not dry_run and updated != original:
            task_file.write_text(updated, encoding="utf-8")

    if not dry_run:
        manifest["mode"] = mode
        manifest["base_hosts"] = base_hosts
        manifest["extra_hosts"] = extra_hosts
        manifest_path.write_text(
            json.dumps(manifest, indent=1, sort_keys=True) + "\n", encoding="utf-8"
        )

    verb = "would patch" if dry_run else "patched"
    print(f"{verb} {patched} task.toml files to network_mode={mode}")
    if mode != "public":
        print(f"verifier phase: network_mode={verifier_mode}")
    if mode == "allowlist":
        print(f"base hosts: {len(base_hosts)}, per-task overrides: {len(task_hosts)}")
    if skipped:
        print(f"tasks without task.toml: {len(skipped)}")
    if not dry_run:
        print(f"manifest: {manifest_path}")
    return 0


def _drop_duplicates(hosts: list[str]) -> list[str]:
    seen: set[str] = set()
    kept: list[str] = []
    for host in hosts:
        if host in seen:
            continue
        seen.add(host)
        kept.append(host)
    return kept


def revert(dataset: Path) -> int:
    manifest_path = dataset / MANIFEST_NAME
    manifest = _load_manifest(manifest_path)
    backups = manifest.get("tasks") or {}
    if not backups:
        print(f"nothing to revert: {manifest_path} has no backups")
        return 1
    restored = 0
    for task, original in backups.items():
        task_file = dataset / "tasks" / task / "task.toml"
        if not task_file.is_file():
            continue
        task_file.write_text(original, encoding="utf-8")
        restored += 1
    manifest_path.unlink()
    print(f"restored {restored} task.toml files; removed {manifest_path}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Pin Harbor's per-task network policy for a dataset checkout"
    )
    parser.add_argument("--dataset-dir", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--mode", choices=MODES, default="allowlist")
    parser.add_argument(
        "--verifier-mode",
        choices=(*MODES, "same"),
        default="public",
        help="network policy for the verifier phase (default: public, because "
        "verifiers install their own tooling; 'same' inherits the environment)",
    )
    parser.add_argument(
        "--hosts",
        type=Path,
        default=DEFAULT_HOSTS,
        help=f"base allowlist file (default: {DEFAULT_HOSTS.name})",
    )
    parser.add_argument(
        "--task-hosts",
        type=Path,
        default=DEFAULT_TASK_HOSTS,
        help=f"per-task allowlist overrides (default: {DEFAULT_TASK_HOSTS.name})",
    )
    parser.add_argument(
        "--extra-host",
        action="append",
        default=[],
        help="additional host for every task; repeatable",
    )
    parser.add_argument(
        "--api-base",
        default=None,
        help="LLM endpoint URL whose host is added to every task",
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--revert", action="store_true", help="restore the original task.toml files"
    )
    args = parser.parse_args(argv)

    if args.revert:
        return revert(args.dataset_dir)

    base_hosts = load_hosts(args.hosts)
    task_hosts = load_task_hosts(args.task_hosts)
    endpoint = resolve_api_host(args.api_base)
    if endpoint:
        base_hosts = _drop_duplicates([endpoint, *base_hosts])
    else:
        print(
            "warning: no LLM endpoint host found; pass --api-base or set "
            "LITELLM_API_BASE, otherwise the agent cannot reach the model",
            file=sys.stderr,
        )
    if args.mode == "no-network" and task_hosts:
        print("note: per-task hosts are ignored in no-network mode")

    return apply_policy(
        args.dataset_dir,
        args.mode,
        args.verifier_mode,
        base_hosts,
        task_hosts,
        list(args.extra_host),
        args.dry_run,
    )


if __name__ == "__main__":
    raise SystemExit(main())
