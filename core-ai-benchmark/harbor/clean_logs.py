"""Strip ANSI/spinner noise from core-ai-cli agent logs.

Harbor keeps the raw terminal stream under ``<trial>/agent/core-ai-cli.txt``.
When the CLI is running without a TTY it still emits the interactive spinner
and cursor-control sequences, which makes the raw file hard to read. This
utility writes a sibling ``core-ai-cli.clean.txt`` with the content preserved
and the spinner frames removed.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

_ANSI = re.compile(
    r"""
    \x1B
    (?:
        [@-Z\\-_]
        |
        \[[0-?]*[ -/]*[@-~]
        |
        \][^\x07]*(?:\x07|\x1B\\)
    )
    """,
    re.VERBOSE,
)
_SPINNER = re.compile(
    r"^\s*[\u2800-\u28FF]\s+.*(?:\(esc to cancel,|tokens \()"
)
_CURSOR_ONLY = re.compile(r"^\s*$")


def clean_text(text: str) -> str:
    text = _ANSI.sub("", text)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = []
    previous = None
    for raw in text.split("\n"):
        line = raw.rstrip()
        if _SPINNER.match(line):
            continue
        if not line.strip():
            if lines and lines[-1] == "":
                continue
            lines.append("")
            previous = ""
            continue
        if line == previous:
            continue
        lines.append(line)
        previous = line
    while lines and lines[-1] == "":
        lines.pop()
    return "\n".join(lines) + "\n"


def clean_file(path: Path) -> Path:
    text = path.read_text(encoding="utf-8", errors="replace")
    output = path.with_name(path.stem + ".clean.txt")
    output.write_text(clean_text(text), encoding="utf-8")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create .clean.txt versions of core-ai-cli agent logs"
    )
    parser.add_argument(
        "path",
        type=Path,
        help="A job directory or a parent directory containing jobs",
    )
    args = parser.parse_args()

    paths = sorted(args.path.glob("*/agent/core-ai-cli.txt"))
    if not paths:
        paths = sorted(args.path.glob("*/*/agent/core-ai-cli.txt"))
    if not paths:
        raise SystemExit(f"No core-ai-cli.txt logs found under {args.path}")

    cleaned = 0
    for path in paths:
        try:
            output = clean_file(path)
        except OSError as exc:
            print(f"Skipped {path}: {exc}")
            continue
        print(f"Cleaned: {output}")
        cleaned += 1
    print(f"Cleaned {cleaned} log(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
