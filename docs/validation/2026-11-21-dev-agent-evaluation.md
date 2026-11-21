# Dev E2E — Offline Agent Evaluation (2.0.332)

Date: 2026-11-21
Environment: dev (`dev-ai`), `https://core-ai-server.connexup-dev.net`
Image: `chancetop/core-ai-server:2.0.332`
Image digest: `sha256:6a27accf0820e1bafe317dd9cafec62a74b6a17dc87efdfd384806900a292be5`
Auth: dev admin API key from the dev `users.api_key` row, set into the browser `localStorage`;
no password was typed into the page.
Migration: V48 applied to the dev PG cluster before rollout (12 `eval_*` tables, Flyway success).

## Result

PASS for the first offline-evaluation subset. The UI, import, spec freeze, Agent execution,
deterministic grading, report, baseline and paired comparison all worked on the real dev stack.

| Step | Input / action | Result |
|---|---|---|
| Page load | `/experiments/evaluations` | 200; empty-state page; no console errors |
| Create evaluation | name `E2E arithmetic`, agent `eval-dev-e2e` | created evaluation `9b718930-4adc-46b5-84b1-533d3f63c18e` |
| Dataset import | 4-case JSONL, all `DEV`, `answer-exact` | revision v1 READY, 4 cases, hash `64c8ee75fbf9` |
| Grader suite | built-in EXACT, `normalization=TRIM` | suite v1 `arithmetic-exact` saved with fingerprint |
| Spec freeze | dataset v1, suite v1, R=1, concurrency=2, deadline=60s, max turns=2 | spec v1, 4 planned trials |
| Run 1 (defective candidate) | PUBLISHED, FORMAL | `COMPLETED`, 0/4 success, 4 failures; required grader `NOT_RUN_TARGET_FAILED`; coverage 100% |
| Baseline | select run 1 | accepted; `baseline_generation=1`; 0% baseline allowed |
| Run 2 (same defective snapshot) | same spec | `COMPLETED`, 0/4; confirms frozen measurement conditions |
| Run 3 (fixed candidate) | same spec, model changed to `deepseek-flash` | `COMPLETED`, 4/4 success, 100% coverage; grader 4 pass |
| Compare | run 3 vs baseline run 1 | `EXPLORATORY`, mean Δ 100%, 95% CI [100%, 100%], 4 wins / 0 losses, 0 regressions; unpinned-model warning shown |
| Cancel | spec v2, R=10, concurrency=1; start then cancel | `CANCELLED`; planned 40 = 1 success + 0 failures + 39 unmeasured; coverage 2.5% |
| Reload | reload the run URL | page and persisted report survive; SPA route works |

Evidence:

- [success report and comparison](assets/agent-evaluation-dev-20261121/success-compare.png)
- [cancelled run with planned denominator](assets/agent-evaluation-dev-20261121/cancel-run.png)

Key DB rows:

```text
evaluation 9b718930-...  baseline_run_id=d70dab3a-...  generation=1
d70dab3a  COMPLETED  4 planned, 0 success, 4 failure, 0 unmeasured
1cba77ef  COMPLETED  4 planned, 0 success, 4 failure, 0 unmeasured
63261540  COMPLETED  4 planned, 4 success, 0 failure, 0 unmeasured
8accd30f  CANCELLED 40 planned, 1 success, 1 failure (pre-fix run), 38 unmeasured
f806eeb1  CANCELLED 40 planned, 1 success, 0 failure, 39 unmeasured
```

The 4 successful trials returned exactly `4`, `63`, `7`, `3` for the arithmetic cases.

## Bugs found and fixed during E2E

### 1. Duplicate core-ng bean names blocked startup (2.0.330)

`EvaluationWebService` exposed `ListRunsRequest`, `ListRunsResponse` and `ListDatasetsResponse`;
core-ng rejects duplicate simple class names across WebService beans, so the first pod
crash-looped at startup. Renamed to `EvalRunListRequest`, `EvalRunListResponse` and
`EvalDatasetListResponse` in 2.0.331. The contract test now validates the evaluation interface
together with the existing run and dataset services using one `BeanClassValidator`.

### 2. User cancel became a task failure (2.0.331)

A cancelled `AgentRun` can be persisted as `FAILED` with `java.util.concurrent.CancellationException`
instead of `CANCELLED`. The gateway mapped that to `AGENT_FAILED`, so the cancelled trial became a
task failure. Fixed in 2.0.332: `CancellationException` maps to `CANCELLED`, and the trial stays
`UNMEASURED`. Verified by the second cancel run (`1 / 0 / 39`).

The first two runs also exposed an unrelated dev-agent issue: `gpt-5-mini` rejects the provider's
default temperature `0.7`. They are kept as valid 0% failure evidence; the eval report correctly
distinguished target failures from grading errors.

## Not covered

- Python and model graders (not implemented in this phase).
- `ISOLATED_SANDBOX` / tool-using agents.
- Artifact and trace capture, exports, regrade.
- Cancel while a trial is in `PREPARING` / grading, and multi-replica scheduler failover.
- A/A, bridge runs and judge calibration.

The dev fixture `eval-dev-e2e` and evaluation `E2E arithmetic` were left in dev for repeat runs.
