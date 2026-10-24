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

| Option | Default                        | Description |
|---|--------------------------------|---|
| `--trials` / `-Trials` | `1`                            | Trials per task. Official leaderboard runs use 5. |
| `--concurrency` / `-Concurrency` | `1`                            | Concurrent trials. Raise only after increasing Docker memory. |
| `--model` / `-Model` | `litellm/deepseek-flash`       | Model in `provider/model` form. |
| `--thinking-effort` / `-ThinkingEffort` | `max`                          | Reasoning effort: `none`, `low`, `medium`, `high`, `max`. |
| `--task` / `-Task` | all                            | Run one task by name. |
| `--n-tasks` / `-NTasks` | all                            | Run the first N tasks. |
| `--jobs-dir` / `-JobsDir` | `.harbor-jobs`                 | Harbor results directory. |
| `--dataset-dir` / `-DatasetDir` | `.datasets/terminal-bench-2-1` | Local dataset checkout. |
| `--dataset-ref` / `-DatasetRef` | `main`                         | Pin a commit or branch. |
| `--cli-binary` / `-CliBinary` | auto-download                  | Prebuilt Linux `core-ai-cli` binary. |
| `--cli-version` / `-CliVersion` | `2.0.61`                       | Release version to download. `2.0.61` is the first release that honours `agent.tools.disabled`. |
| `--disabled-tools` / `-DisabledTools` | `web_search,web_fetch`         | Tools hidden from the agent (written to `agent.tools.disabled`). Empty disables nothing. |
| `--network-mode` / `-NetworkMode` | dataset default (`public`)      | Pin the dataset network policy: `allowlist`, `no-network`, or `public`. See below. |
| `--allow-host` / `-AllowHost` | none                           | Extra egress host for every task; repeatable. |
| `--update-dataset` / `-UpdateDataset` | off                            | `git pull` the local dataset first. |

Environment equivalents: `TRIALS`, `CONCURRENCY`, `MODEL`, `THINKING_EFFORT`,
`TASK`, `N_TASKS`, `HARBOR_JOBS_DIR`, `TB21_DATASET_DIR`,
`TB21_DATASET_REF`, `CORE_AI_CLI_VERSION`, `CORE_AI_CLI_BINARY`, `HARBOR`,
`DISABLED_TOOLS`, `NETWORK_MODE`.

## What the runner does

1. Reads the active `~/.core-ai/auth.json` entry (or `LITELLM_API_*` overrides).
2. Ensures Harbor 0.24+ is available.
3. Downloads `core-ai-cli-linux.tar.gz` from the pinned GitHub release when the
   local `.cache/core-ai-cli-linux` is missing or was cached for another
   `--cli-version`.
4. Clones `harbor-framework/terminal-bench-2-1` when `.datasets/` is missing.
6. Applies the network policy to the dataset when `--network-mode` is set (see
   below), rewriting `[environment]` in every `task.toml`.
7. Runs Harbor with `-a cli:CoreAiCli` and the binary path passed through
   `--ak binary_path=...`.
8. Writes results to `.harbor-jobs/<timestamp>/`.
9. Summarizes the job into `<job>/report.md` and `<job>/summary.csv`, writes
   readable `<trial>/agent/core-ai-cli.clean.txt` logs, and scans the
   transcripts into `<job>/contamination.md`.

The report includes pass/fail/error counts, pass rates, mean reward, token
totals, and a per-task table. Infra errors are listed separately from model
failures. A `pass*` row means the verifier returned reward >= 1.0 but the
agent phase raised an exception (usually a timeout); it is counted separately
from a clean pass.

The adapter lives in `cli.py`. It installs the native binary into the task
container, writes `~/.core-ai/agent.properties` with the resolved provider and
model, runs one `--prompt` turn with `--dangerously-skip-permissions`, and
parses the final token usage line into Harbor's result statistics.

## Network access and contamination

Terminal-Bench tasks are published with their tests and reference solution in
`harbor-framework/terminal-bench-2-1`, so a container with public network access
can read its own answer key. The 2026-10-23 run did exactly that: 7 tasks fetched
`tasks/<task>/solution.sh` or `tests/test.sh` from GitHub, and all 7 passed.

Two independent layers keep that out of a score:

1. **Tools.** `agent.tools.disabled=web_search,web_fetch` removes the web tools
   from the registry: they are neither advertised to the model nor dispatchable,
   and sub-agents inherit the restriction. This needs CLI **2.0.61+** — earlier
   binaries ignore the property without a warning.
2. **Container egress.** Tools alone are not enough, because the agent still has
   a shell and `curl` reaches the same URLs. `--network-mode allowlist` (or
   `no-network`) makes Harbor's egress sidecar enforce the policy below the
   shell.

Allowlist mode is default-deny, so the hosts a task legitimately needs must be
listed:

- `network_allowlist.txt` — hosts every task may reach (package mirrors, CRAN,
  PyPI, npm, crates, Go). Keep the benchmark repositories out of it.
- `network_hosts.json` — per-task extras for the inputs a task cannot run
  without, e.g. a model checkpoint or a dataset mirror. Do not add documentation
  sites, walkthroughs, leaderboards or anything that states the expected result;
  a task that needs one of those to pass is a task this baseline cannot measure
  cleanly.
- The agent's LLM endpoint is added automatically from `LITELLM_API_BASE` or the
  active `~/.core-ai/auth.json` entry (override with `--api-base`).

A task that fails with a DNS or TLS error under `allowlist` is missing a host:
add it to `network_hosts.json` and rerun. The rewrite is recorded in
`.datasets/terminal-bench-2-1/.network-policy.json`; `python
core-ai-benchmark/harbor/network_policy.py --revert` restores the upstream
`task.toml` files.

`contamination.py` audits a finished job no matter which layers were active:

```shell
python core-ai-benchmark/harbor/contamination.py .harbor-jobs/<job>
```

It writes `<job>/contamination.md` and `contamination.csv`, and reports the raw
pass rate next to the clean-trial pass rate. Verdicts: `answer_key` (a tool call
referenced its own task path), `benchmark_repo`, `web_external` (used a web
tool), `clean`. `--strict` exits 2 when any trial read its own answer key, so a
pipeline can refuse to publish a contaminated score.

## Results

```shell
harbor view core-ai-benchmark/harbor/.harbor-jobs
```

Useful files:

- `report.md` — human-readable summary with pass rate, tokens, and a per-task table.
- `summary.csv` — the same per-task data in machine-readable form.
- `contamination.md` / `contamination.csv` — which trials reached the network or
  the benchmark repositories, plus the raw vs clean pass rate.
- `<task>__<id>/agent/core-ai-cli.clean.txt` — spinner/ANSI-free copy of the agent log.
- `result.json` — job summary, `stats.evals.*.metrics[0].mean` is the mean reward.
- `<task>__<id>/verifier/reward.txt` — per-trial reward.
- `<task>__<id>/verifier/ctrf.json` — verifier test details.
- `<task>__<id>/agent/core-ai-cli.txt` — full agent log.

## Notes

- **Disabled tools need 2.0.61+.** v2.0.60 and older ignore
  `agent.tools.disabled` silently, so `job.log` can show the property while the
  web tools are still live. Native binaries keep property names as plain
  strings, so the binary itself can be checked:
  `strings .cache/core-ai-cli-linux | grep agent.tools.disabled`.
- **Windows console encoding.** Harbor reads each task's `instruction.md` with
  the locale default encoding. On Chinese (GBK/cp936) Windows the UTF-8 tasks
  (`distribution-search`, `llm-inference-batching-scheduler`) raise
  `UnicodeDecodeError`, which aborts the job's task group and surfaces as
  `CancelledError` on every in-flight trial. Both runners export
  `PYTHONUTF8=1` to force UTF-8; keep that env var set when invoking Harbor
  directly. Harbor's rich spinner also needs it, otherwise it fails with
  `UnicodeEncodeError: 'gbk' codec can't encode character '\u280b'`.
- **Resume.** Pass `--job-name <job-dir-name>` (PowerShell: `-JobName`) to
  re-enter an existing job directory; completed trials are skipped and the rest
  run again. Delete a cancelled trial directory first if it should be retried.
- **Old-glibc tasks.** `qemu-alpine-ssh` and `qemu-startup` run on Debian
  bullseye (glibc 2.31), while CLI builds up to v2.0.53 link against a newer
  glibc and die with `GLIBC_2.32 not found`. Keep the default v2.0.55 (or newer)
  when running them. Their verifiers also pin bullseye-security package versions
  that Debian has since deleted from the pool, so `apt-get install` returns 404;
  install with `-t bullseye` in `tests/test.sh` to read from the main suite
  instead.
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
- **Logs.** v2.0.54+ suppresses the interactive spinner and cursor-control output
  in `--prompt` mode and when there is no console, so raw logs are already
  readable. `summarize.py` still writes a spinner-free
  `core-ai-cli.clean.txt` next to each raw log for older binaries. The cached
  binary is re-downloaded whenever the requested `--cli-version` changes; delete
  `.cache/` to force a refresh.
- **Infra failures.** `git-multibranch` once failed with
  `Docker daemon is not running` during a Docker Desktop restart, then passed
  on retry. Treat Harbor exceptions/unfinished trials separately from model
  failures.
- **Windows.** PowerShell runs the full flow natively. WSL2/Git Bash works too.
