# Harbor benchmark for core-ai-cli

Run [Terminal-Bench 2.1](https://www.tbench.ai/leaderboard/terminal-bench/2.1)
against `core-ai-cli` with [Harbor](https://github.com/harbor-framework/harbor).

The runner is a full-benchmark entrypoint: it clones the dataset, downloads the
pinned Linux binary, resolves the active core-ai login, and runs all 89 tasks.
A 10-task pilot on Windows + Docker passed 10/10 (one Docker daemon restart was
retried; see the notes below).

## Prerequisites

- Python 3.12+
- Docker (Linux containers, x86_64)
- [uv](https://docs.astral.sh/uv/)
- `git`, `curl` (or `wget`), and `tar` for the automatic dataset/binary setup
- A core-ai login: `core-ai-cli --login <server-url>`

Install the pinned Harbor version:

```shell
uv tool install "harbor==0.24.0"
```

## Quick start

```shell
# Linux / macOS / WSL / Git Bash
bash core-ai-benchmark/harbor/run.sh
```

```powershell
# Windows PowerShell
.\core-ai-benchmark\harbor\run.ps1
```

By default this runs:

- all 89 tasks from `terminal-bench/terminal-bench-2-1`;
- 1 trial per task;
- 1 concurrent trial;
- model `litellm/deepseek-flash`;
- reasoning effort `max`;
- results under `core-ai-benchmark/harbor/.harbor-jobs/`.

Authentication is automatic: the runner reads the active entry in
`~/.core-ai/auth.json` and uses `<server_url>/api/cli/v1` as the LiteLLM base
and its `api_key`. To override, set `LITELLM_API_BASE` and `LITELLM_API_KEY`
(or put them in `core-ai-benchmark/harbor/.env`).

## Common options

Bash:

```shell
bash core-ai-benchmark/harbor/run.sh \
  --trials 1 \
  --concurrency 2 \
  --model litellm/deepseek-flash \
  --n-tasks 10
```

PowerShell:

```powershell
.\core-ai-benchmark\harbor\run.ps1 -Trials 1 -Concurrency 2 -NTasks 10
```

| Option | Default | Description |
|---|---|---|
| `--trials` / `-Trials` | `1` | Trials per task. Official leaderboard runs use 5. |
| `--concurrency` / `-Concurrency` | `1` | Concurrent trials. Raise only after increasing Docker memory. |
| `--model` / `-Model` | `litellm/deepseek-flash` | Model in `provider/model` form. |
| `--thinking-effort` / `-ThinkingEffort` | `max` | Reasoning effort: `none`, `low`, `medium`, `high`, `max`. |
| `--task` / `-Task` | all | Run one task by name. |
| `--n-tasks` / `-NTasks` | all | Run the first N tasks. |
| `--jobs-dir` / `-JobsDir` | `.harbor-jobs` | Harbor results directory. |
| `--dataset-dir` / `-DatasetDir` | `.datasets/terminal-bench-2-1` | Local dataset checkout. |
| `--dataset-ref` / `-DatasetRef` | `main` | Pin a commit or branch. |
| `--cli-binary` / `-CliBinary` | auto-download | Prebuilt Linux `core-ai-cli` binary. |
| `--cli-version` / `-CliVersion` | `2.0.52` | Release version to download. |
| `--update-dataset` / `-UpdateDataset` | off | `git pull` the local dataset first. |

Environment equivalents: `TRIALS`, `CONCURRENCY`, `MODEL`, `THINKING_EFFORT`,
`TASK`, `N_TASKS`, `HARBOR_JOBS_DIR`, `TB21_DATASET_DIR`,
`TB21_DATASET_REF`, `CORE_AI_CLI_VERSION`, `CORE_AI_CLI_BINARY`, `HARBOR`.

## What the runner does

1. Reads the active `~/.core-ai/auth.json` entry (or `LITELLM_API_*` overrides).
2. Ensures Harbor 0.24+ is available.
3. Downloads `core-ai-cli-linux.tar.gz` from the pinned GitHub release when the
   local `.cache/core-ai-cli-linux` is missing.
4. Clones `harbor-framework/terminal-bench-2-1` when `.datasets/` is missing.
5. Runs Harbor with `-a cli:CoreAiCli` and the binary path passed through
   `--ak binary_path=...`.
6. Writes results to `.harbor-jobs/<timestamp>/`.
7. Summarizes the job into `<job>/report.md` and `<job>/summary.csv`, and
   writes readable `<trial>/agent/core-ai-cli.clean.txt` logs.

The report includes pass/fail/error counts, pass rates, mean reward, token
totals, and a per-task table. Infra errors are listed separately from model
failures. A `pass*` row means the verifier returned reward >= 1.0 but the
agent phase raised an exception (usually a timeout); it is counted separately
from a clean pass.

The adapter lives in `cli.py`. It installs the native binary into the task
container, writes `~/.core-ai/agent.properties` with the resolved provider and
model, runs one `--prompt` turn with `--dangerously-skip-permissions`, and
parses the final token usage line into Harbor's result statistics.

## Results

```shell
harbor view core-ai-benchmark/harbor/.harbor-jobs
```

Useful files:

- `report.md` — human-readable summary with pass rate, tokens, and a per-task table.
- `summary.csv` — the same per-task data in machine-readable form.
- `<task>__<id>/agent/core-ai-cli.clean.txt` — spinner/ANSI-free copy of the agent log.
- `result.json` — job summary, `stats.evals.*.metrics[0].mean` is the mean reward.
- `<task>__<id>/verifier/reward.txt` — per-trial reward.
- `<task>__<id>/verifier/ctrf.json` — verifier test details.
- `<task>__<id>/agent/core-ai-cli.txt` — full agent log.

## Notes

- **Docker memory.** The benchmark task containers request ~2 GB each. The
  runner defaults to `--concurrency 1` so it also works on small Docker Desktop
  VMs. Increase Docker Desktop memory to 8–12 GB before raising concurrency.
- **Trials.** Local smoke tests use 1 trial. Official Terminal-Bench 2.1
  leaderboard runs use 5 trials per task (`--trials 5`), but community
  submissions for 2.1 are currently closed; use this runner for internal
  comparisons.
- **Model.** `litellm/deepseek-flash` is the default. Set `--model` to a model
  available on your core-ai server.
- **Thinking effort.** The runner defaults to `max` and passes it through
  `--ak reasoning_effort=...`, which the adapter writes into
  `llm.request.extra_body`.
- **Dataset pinning.** The 10-task pilot used dataset commit
  `7131e4375048a0e408a8fb404b5f499d726b695b`. Set
  `--dataset-ref 7131e4375048a0e408a8fb404b5f499d726b695b` to reproduce it.
- **Logs.** The pinned v2.0.52 binary still emits the interactive spinner when
  stdout is a pipe; `summarize.py` writes a spinner-free
  `core-ai-cli.clean.txt` next to each raw log. The CLI source now disables the
  spinner/ANSI when there is no console, so a future release will produce clean
  logs directly.
- **Infra failures.** `git-multibranch` once failed with
  `Docker daemon is not running` during a Docker Desktop restart, then passed
  on retry. Treat Harbor exceptions/unfinished trials separately from model
  failures.
- **Windows.** PowerShell runs the full flow natively. WSL2/Git Bash works too.
