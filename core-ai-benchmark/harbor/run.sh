#!/bin/bash
# Harbor benchmark runner for core-ai-cli
# Usage: run.sh <dataset> [task-filter]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ---- Load .env ----
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a; source "$SCRIPT_DIR/.env"; set +a
fi

# ---- Auto-detect install_dir ----
if [ -z "${CORE_AI_INSTALL_DIR:-}" ]; then
    CANDIDATE="$REPO_ROOT/build/core-ai-cli/install/core-ai-cli"
    if [ -d "$CANDIDATE" ]; then
        CORE_AI_INSTALL_DIR="$CANDIDATE"
    fi
fi

# ---- Validate ----
if [ -z "${CORE_AI_INSTALL_DIR:-}" ] || [ ! -d "$CORE_AI_INSTALL_DIR" ]; then
    echo "❌ CORE_AI_INSTALL_DIR not found or not set."
    echo "   Build it first: ./gradlew :core-ai-cli:installDist"
    echo "   Or set CORE_AI_INSTALL_DIR in .env"
    exit 1
fi

if [ -z "${CORE_AI_JRE_PATH:-}" ] || [ ! -d "$CORE_AI_JRE_PATH" ]; then
    echo "❌ CORE_AI_JRE_PATH not found or not set."
    echo "   Run: make jre25-linux"
    echo "   Or set CORE_AI_JRE_PATH in .env"
    exit 1
fi

DATASET="${1:-}"
TASK="${2:-}"

if [ -z "$DATASET" ]; then
    echo "Usage: run.sh <dataset> [task-filter]"
    echo ""
    echo "Examples:"
    echo "  run.sh terminal-bench@2.0 write-compressor"
    echo "  run.sh swebench@2.0"
    echo ""
    echo "Setup:"
    echo "  cp .env.example .env   # then edit .env with your API keys"
    echo "  ./gradlew :core-ai-cli:installDist"
    exit 1
fi

# ---- Set up PYTHONPATH for --agent-import-path ----
# harbor/ must be on PYTHONPATH so importlib finds cli:CoreAiCli
export PYTHONPATH="$SCRIPT_DIR:${PYTHONPATH:-}"

# ---- Build harbor command ----
HARBOR_CMD=(
    harbor run
    --agent-import-path "cli:CoreAiCli"
    --model "${CORE_AI_MODEL:-litellm/gpt-5.4-nano}"
    --dataset "$DATASET"
    --jobs-dir "$SCRIPT_DIR/.harbor-jobs"
    --agent-kwarg "install_dir=$CORE_AI_INSTALL_DIR"
    --agent-kwarg "jre_path=$CORE_AI_JRE_PATH"
)

# Optional params
if [ -n "$TASK" ]; then
    HARBOR_CMD+=(-i "$TASK")
fi
if [ -n "${LITELLM_API_KEY:-}" ]; then
    HARBOR_CMD+=(--ae "LITELLM_API_KEY=$LITELLM_API_KEY")
fi
if [ -n "${LITELLM_API_BASE:-}" ]; then
    HARBOR_CMD+=(--ae "LITELLM_API_BASE=$LITELLM_API_BASE")
fi

echo "🏃 ${HARBOR_CMD[*]}"
exec "${HARBOR_CMD[@]}"
