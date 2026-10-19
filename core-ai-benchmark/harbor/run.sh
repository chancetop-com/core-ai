#!/usr/bin/env bash
# Run the full Terminal-Bench 2.1 benchmark against core-ai-cli.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

HARBOR="${HARBOR:-harbor}"
TRIALS="${TRIALS:-1}"
CONCURRENCY="${CONCURRENCY:-1}"
MODEL="${MODEL:-litellm/deepseek-flash}"
THINKING_EFFORT="${THINKING_EFFORT-max}"
TASK="${TASK:-}"
N_TASKS="${N_TASKS:-}"
JOBS_DIR="${HARBOR_JOBS_DIR:-$SCRIPT_DIR/.harbor-jobs}"
DATASET_DIR="${TB21_DATASET_DIR:-$SCRIPT_DIR/.datasets/terminal-bench-2-1}"
DATASET_REF="${TB21_DATASET_REF:-main}"
CLI_VERSION="${CORE_AI_CLI_VERSION:-2.0.52}"
CLI_BINARY="${CORE_AI_CLI_BINARY:-$SCRIPT_DIR/.cache/core-ai-cli-linux}"
UPDATE_DATASET=0

usage() {
    cat <<'EOF'
Usage: run.sh [options]

Runs the full Terminal-Bench 2.1 dataset (89 tasks) against core-ai-cli.
Defaults: 1 trial, 1 concurrent trial, model litellm/deepseek-flash,
thinking effort max.

Options:
  --trials N            Trials per task (default: 1; official leaderboard uses 5)
  --concurrency N       Concurrent trials (default: 1; raise after increasing Docker memory)
  --model MODEL         Model in provider/model form (default: litellm/deepseek-flash)
  --thinking-effort E   Reasoning effort: none|low|medium|high|max (default: max)
  --task NAME           Run one task only (repeatable flag not required)
  --n-tasks N           Run the first N tasks only
  --jobs-dir DIR        Harbor jobs directory (default: .harbor-jobs)
  --dataset-dir DIR     Local Terminal-Bench 2.1 checkout (default: .datasets/terminal-bench-2-1)
  --dataset-ref REF     Git ref for the dataset (default: main)
  --cli-binary PATH     Prebuilt Linux core-ai-cli binary
  --cli-version VER     core-ai-cli release version to download (default: 2.0.52)
  --update-dataset      git pull the local dataset before running
  -h, --help            Show this help

Authentication:
  By default the active entry in ~/.core-ai/auth.json (written by
  `core-ai-cli --login`) is used. LITELLM_API_BASE / LITELLM_API_KEY override it.

Environment:
  HARBOR, TRIALS, CONCURRENCY, MODEL, THINKING_EFFORT, TASK, N_TASKS,
  HARBOR_JOBS_DIR, TB21_DATASET_DIR, TB21_DATASET_REF,
  CORE_AI_CLI_VERSION, CORE_AI_CLI_BINARY.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --trials) TRIALS="$2"; shift 2 ;;
        --concurrency) CONCURRENCY="$2"; shift 2 ;;
        --model) MODEL="$2"; shift 2 ;;
        --thinking-effort) THINKING_EFFORT="$2"; shift 2 ;;
        --task) TASK="$2"; shift 2 ;;
        --n-tasks) N_TASKS="$2"; shift 2 ;;
        --jobs-dir) JOBS_DIR="$2"; shift 2 ;;
        --dataset-dir) DATASET_DIR="$2"; shift 2 ;;
        --dataset-ref) DATASET_REF="$2"; shift 2 ;;
        --cli-binary) CLI_BINARY="$2"; shift 2 ;;
        --cli-version) CLI_VERSION="$2"; shift 2 ;;
        --update-dataset) UPDATE_DATASET=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || command -v python || true)}"
if [ -z "$PYTHON_BIN" ]; then
    echo "Python 3 is required to read ~/.core-ai/auth.json" >&2
    exit 1
fi

if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$SCRIPT_DIR/.env"
    set +a
fi

resolve_auth() {
    if [ -n "${LITELLM_API_BASE:-}" ] && [ -n "${LITELLM_API_KEY:-}" ]; then
        return
    fi
    local auth_file="$HOME/.core-ai/auth.json"
    if [ ! -f "$auth_file" ]; then
        return
    fi
    eval "$("$PYTHON_BIN" - "$auth_file" <<'PY'
import json
import os
import shlex
import sys

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as fh:
        payload = json.load(fh)
except (OSError, ValueError):
    raise SystemExit(0)

entry = None
if isinstance(payload, list):
    entry = next(
        (item for item in payload if isinstance(item, dict) and item.get("active") is True),
        None,
    )
elif isinstance(payload, dict):
    entry = payload

if not entry:
    raise SystemExit(0)

base = os.environ.get("LITELLM_API_BASE")
key = os.environ.get("LITELLM_API_KEY")
server_url = str(entry.get("server_url") or "").rstrip("/")
if not base and server_url:
    print("export LITELLM_API_BASE=" + shlex.quote(server_url + "/api/cli/v1"))
if not key and entry.get("api_key"):
    print("export LITELLM_API_KEY=" + shlex.quote(str(entry["api_key"])))
PY
)"
}
resolve_auth

if [ -z "${LITELLM_API_BASE:-}" ] || [ -z "${LITELLM_API_KEY:-}" ]; then
    echo "No core-ai credentials found." >&2
    echo "Run 'core-ai-cli --login <server-url>' first, or set LITELLM_API_BASE and LITELLM_API_KEY." >&2
    exit 1
fi

if [ -x "$HARBOR" ]; then
    :
elif ! command -v "$HARBOR" >/dev/null 2>&1; then
    echo "Harbor is not installed. Run: uv tool install 'harbor==0.24.0'" >&2
    exit 1
fi

HARBOR_VERSION="$( "$HARBOR" --version 2>&1 | head -n 1 )"
echo "Harbor: $HARBOR_VERSION"
case "$HARBOR_VERSION" in
    0.2[4-9]*|0.[3-9]*|[1-9]*) ;;
    *)
        echo "Harbor 0.24+ is required (found: $HARBOR_VERSION)." >&2
        echo "Run: uv tool install 'harbor==0.24.0'" >&2
        exit 1
        ;;
esac

ensure_cli() {
    if [ -n "${CORE_AI_CLI_BINARY:-}" ]; then
        if [ ! -f "$CLI_BINARY" ]; then
            echo "CORE_AI_CLI_BINARY not found: $CLI_BINARY" >&2
            exit 1
        fi
        chmod +x "$CLI_BINARY" 2>/dev/null || true
        return
    fi
    if [ -x "$CLI_BINARY" ]; then
        return
    fi
    mkdir -p "$(dirname "$CLI_BINARY")"
    local tmp_dir
    tmp_dir="$(mktemp -d)"
    trap 'rm -rf "$tmp_dir"' RETURN
    local url
    if [ "$CLI_VERSION" = "latest" ]; then
        url="https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-linux.tar.gz"
    else
        url="https://github.com/chancetop-com/core-ai/releases/download/v${CLI_VERSION}/core-ai-cli-linux.tar.gz"
    fi
    echo "Downloading core-ai-cli v${CLI_VERSION}..."
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url" -o "$tmp_dir/core-ai-cli-linux.tar.gz"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$url" -O "$tmp_dir/core-ai-cli-linux.tar.gz"
    else
        echo "curl or wget is required to download core-ai-cli" >&2
        exit 1
    fi
    tar -xzf "$tmp_dir/core-ai-cli-linux.tar.gz" -C "$tmp_dir"
    mv "$tmp_dir/core-ai-cli-linux" "$CLI_BINARY"
    chmod +x "$CLI_BINARY"
}
ensure_cli

ensure_dataset() {
    if [ ! -d "$DATASET_DIR/tasks" ]; then
        if [ -d "$DATASET_DIR" ] && [ -n "$(ls -A "$DATASET_DIR" 2>/dev/null || true)" ]; then
            echo "Dataset directory exists but has no tasks/: $DATASET_DIR" >&2
            exit 1
        fi
        echo "Cloning Terminal-Bench 2.1 dataset..."
        rm -rf "$DATASET_DIR"
        git clone --depth 1 https://github.com/harbor-framework/terminal-bench-2-1.git "$DATASET_DIR"
    fi
    if [ "$UPDATE_DATASET" -eq 1 ]; then
        git -C "$DATASET_DIR" pull --ff-only
    fi
    if [ "$DATASET_REF" != "main" ]; then
        git -C "$DATASET_DIR" fetch --depth 1 origin "$DATASET_REF"
        git -C "$DATASET_DIR" checkout -q FETCH_HEAD
    fi
}
ensure_dataset

if ! [[ "$TRIALS" =~ ^[0-9]+$ ]] || [ "$TRIALS" -lt 1 ]; then
    echo "--trials must be a positive integer" >&2
    exit 2
fi
if ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] || [ "$CONCURRENCY" -lt 1 ]; then
    echo "--concurrency must be a positive integer" >&2
    exit 2
fi

export PYTHONPATH="$SCRIPT_DIR${PYTHONPATH:+:$PYTHONPATH}"

HARBOR_ARGS=(
    run
    -p "$DATASET_DIR/tasks"
    -a "cli:CoreAiCli"
    --ak "binary_path=$CLI_BINARY"
    -n "$CONCURRENCY"
    -k "$TRIALS"
    -y
    --jobs-dir "$JOBS_DIR"
)
if [ -n "$THINKING_EFFORT" ]; then
    HARBOR_ARGS+=(--ak "reasoning_effort=$THINKING_EFFORT")
fi
if [ -n "$MODEL" ]; then
    HARBOR_ARGS+=(-m "$MODEL")
fi
if [ -n "$TASK" ]; then
    HARBOR_ARGS+=(-i "$TASK")
fi
if [ -n "$N_TASKS" ]; then
    HARBOR_ARGS+=(-l "$N_TASKS")
fi

echo "Dataset: $DATASET_DIR ($(git -C "$DATASET_DIR" rev-parse --short HEAD))"
echo "core-ai-cli: $CLI_BINARY"
echo "Model: $MODEL"
echo "Thinking effort: ${THINKING_EFFORT:-none}"
echo "Trials: $TRIALS, concurrency: $CONCURRENCY"
echo "Jobs: $JOBS_DIR"

set +e
"$HARBOR" "${HARBOR_ARGS[@]}"
HARBOR_EXIT=$?
set -e

if [ -f "$SCRIPT_DIR/summarize.py" ]; then
    "$PYTHON_BIN" "$SCRIPT_DIR/summarize.py" "$JOBS_DIR" || true
fi

echo
echo "Results: $JOBS_DIR"
echo "Inspect: $HARBOR view \"$JOBS_DIR\""
echo "Official leaderboard note: Terminal-Bench 2.1 community submissions are closed."
exit "$HARBOR_EXIT"
