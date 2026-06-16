# Harbor Benchmarks for core-ai-cli

Run [Harbor](https://github.com/harbor-framework/harbor) benchmarks against `core-ai-cli`.

## Quick Start

```bash
# 1. Install Harbor (first time only)
make install-harbor

# 2. Build the CLI distribution
make cli-dist

# 3. Download JRE for offline container use
make jre25-linux

# 4. Configure API keys
cp core-ai-benchmark/harbor/.env.example core-ai-benchmark/harbor/.env
# Edit .env with your LiteLLM / provider credentials

# 5. Run a benchmark
bash core-ai-benchmark/harbor/run.sh terminal-bench@2.0 write-compressor
```

Or using Make:

```bash
make benchmark DATASET=terminal-bench@2.0 TASK=write-compressor
```

## Configuration

All settings are in `.env` (copy from `.env.example`):

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `LITELLM_API_KEY` | Yes | - | LiteLLM API key |
| `LITELLM_API_BASE` | Yes | - | LiteLLM API base URL |
| `CORE_AI_JRE_PATH` | Yes | `/tmp/jre25-linux` | Pre-extracted Linux JRE (run `make jre25-linux`) |
| `CORE_AI_MODEL` | No | `litellm/gpt-5.4-nano` | Model in `provider/model` format |
| `CORE_AI_INSTALL_DIR` | No | auto | Path to `installDist` output |

## Usage

```bash
# Run all tasks in a dataset
bash core-ai-benchmark/harbor/run.sh terminal-bench@2.0

# Filter by task name
bash core-ai-benchmark/harbor/run.sh terminal-bench@2.0 write-compressor

# Override model
CORE_AI_MODEL=openrouter/anthropic/claude-sonnet-4-5 bash core-ai-benchmark/harbor/run.sh swebench@2.0
```

## Supported Datasets

Any dataset from the [Harbor Registry](https://github.com/harbor-framework/registry) works. Examples:

- `terminal-bench@2.0` — Terminal-based tasks
- `swebench@2.0` — Software engineering bugs
- `aider-polyglot@2.0` — Multi-language code generation
- `aime@1.0` — Math reasoning

See available datasets: `harbor datasets list`

## How It Works

The script wraps `harbor run` with:
- `--agent-import-path cli:CoreAiCli` — dynamically loads the agent from `harbor/cli.py` via Python `importlib`
- `--agent-kwarg install_dir=...` — points to the Gradle `installDist` output (auto-detected)
- `--agent-kwarg jre_path=...` — provides offline JRE for the container
- `--ae LITELLM_API_KEY=...` — passes API credentials to the container
- `PYTHONPATH` — set to `harbor/` dir so the `cli` module is importable

Agent code lives in `core-ai-benchmark/harbor/cli.py` — self-contained, no Harbor source modifications needed.

Results are stored in `core-ai-benchmark/harbor/.harbor-jobs/` (git-ignored).
