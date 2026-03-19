# Ablation Study: Hook-Driven Self-Improvement Trigger

## Research Question

Does the `UserPromptSubmit` hook — which injects `<self-improvement-reminder>` into every user message — cause the agent to automatically invoke the `self-improvement` skill after completing a task that contains implicit learning opportunities?

Without the hook, no explicit instruction is given to the agent to capture learnings.

---

## Test Assets

### Unit Test: Hook Mechanism

`core-ai-cli/src/test/java/ai/core/cli/hook/HookAblationTest.java`

Verifies the injection mechanism itself — deterministic, no LLM required:

| Test | What it verifies |
|------|-----------------|
| `withoutHooks_queryUnchanged` | No hook → query unmodified |
| `withHooks_reminderAppendedToQuery` | UserPromptSubmit → reminder injected |
| `withHooks_errorDetectedOnFailedShellOutput` | PostToolUse → error notice injected |
| `withHooks_noInjectionOnSuccessfulShellOutput` | PostToolUse → no injection on success |
| `withHooks_matcherFiltersNonShellTools` | matcher → only matching tool name triggers |
| `withHooks_sessionStartOutputInjectedIntoSystemPrompt` | SessionStart → output into system prompt |
| `withoutHooks_sessionStartOutputIsEmpty` | No hook → no SessionStart output |

Run:
```bash
./gradlew core-ai-cli:test --tests "ai.core.cli.hook.HookAblationTest"
```

### Integration Script: LLM Behavior

`docs/skillwithhook/validate-self-improving-ablation.sh`

Runs core-ai-cli with a real LLM against 12 scenarios × 2 conditions (no hook / with hook), each repeated 10 times. Observes whether the agent calls `use_skill("self-improvement")`.

```bash
# Prerequisites: core-ai-cli built, API key in ~/.core-ai/agent.properties
./gradlew core-ai-cli:installDist
bash docs/skillwithhook/validate-self-improving-ablation.sh
```

The script creates two isolated temp workspaces, copies the `self-improvement` skill into both, adds `hooks.json` only to the treatment workspace, then pipes each query to the CLI as stdin:

```
printf '<query>\n/exit\n' | core-ai-cli --dangerously-skip-permissions --workspace <ws>
```

Output: per-scenario hit counts split into True Positive and False Positive groups.

---

## Experimental Design

### Two Groups of Scenarios

**True Positive (T1–T6)** — queries that contain implicit learning signals and *should* trigger `use_skill`.
**False Positive (FP1–FP6)** — routine queries outside the skill's trigger conditions that *must not* trigger `use_skill`.

### True Positive Scenarios

All 6 queries contain **implicit** learning signals — no instruction to capture learnings. Each matches one condition from the skill's description.

| ID | Trigger Condition | Query Signal |
|----|------------------|-------------|
| T1 | Command fails unexpectedly | Gradle proxy HTTPS resolution took 1 hour |
| T2 | User corrects agent | Wrong `@Transactional` advice given by agent |
| T3 | Capability gap | Agent didn't know Jacoco task ordering |
| T4 | External API fails | OpenAI 429 from client-per-request misuse |
| T5 | Knowledge outdated | `java.util.Date` flagged in code review |
| T6 | Better approach found | Centralized `ObjectMapper` bean saves 40 lines |

### False Positive Scenarios

Routine queries with no error, no discovery, and no learning signal.

| ID | Scenario | Query |
|----|----------|-------|
| FP1 | Basic syntax question | What is the syntax for a Java enhanced for loop? |
| FP2 | Feature completed normally | GET /users/{id} implemented, all 12 tests pass |
| FP3 | Factual question | How many elements can a Java int array hold at most? |
| FP4 | Build green | Full test suite ran, all 247 tests pass |
| FP5 | Standard PR review | Payment module PR reviewed and approved |
| FP6 | Routine config | logback.xml set up following team template |

### Independent Variable: hooks.json

**Control** — no `hooks.json` in workspace.

**Treatment** — `hooks.json` injects `<self-improvement-reminder>` via `activator.sh` on every `UserPromptSubmit`:

```xml
<self-improvement-reminder>
After completing this task, evaluate if extractable knowledge emerged:
- Non-obvious solution discovered through investigation?
- Workaround for unexpected behavior?
If yes: Log to .learnings/ using the self-improvement skill format.
</self-improvement-reminder>
```

---

## Results (10 runs per scenario)

### True Positive — should trigger

| ID | Trigger Condition | No Hook | With Hook |
|----|------------------|---------|-----------|
| T1 | Command fails unexpectedly | 0/10 | **9/10** |
| T2 | User corrects agent | 10/10 | 10/10 |
| T3 | Capability gap | 5/10 | **9/10** |
| T4 | External API fails | 0/10 | **8/10** |
| T5 | Outdated knowledge | 1/10 | 1/10 |
| T6 | Better approach found | 0/10 | **3/10** |
| **Group total** | | **16/60 (27%)** | **40/60 (67%)** |

### False Positive — must NOT trigger

| ID | Scenario | No Hook | With Hook |
|----|----------|---------|-----------|
| FP1 | Routine syntax question | 0/10 | 0/10 |
| FP2 | Feature completed normally | 0/10 | 0/10 |
| FP3 | Factual language question | 0/10 | 0/10 |
| FP4 | Tests pass, build green | 0/10 | 0/10 |
| FP5 | Standard PR review, approved | 0/10 | 0/10 |
| FP6 | Routine config (logback setup) | 0/10 | 0/10 |
| **Group total** | | **0/60 (0%)** | **0/60 (0%)** |

LLM: `minimax/minimax-m2.5` · Date: 2026-03-19

---

## Conclusions

**1. Hook raises TP trigger rate from 27% to 67%.**

Without the reminder, the agent completes tasks and stops in most cases. With the hook, it evaluates the session and calls `use_skill` significantly more often. The improvement is consistent across scenarios that involve errors, corrections, or capability gaps.

**2. Strong signals self-trigger without a hook (T2).**

"User corrects agent" (T2) triggered 10/10 even without the hook. When the signal is explicit — the agent recognizes it gave wrong advice — it captures the learning regardless. The hook is most valuable for subtle signals (T1, T4) or positive-outcome discoveries (T6).

**3. The hook does not guarantee triggering for all scenario types.**

T5 ("Outdated knowledge") triggered only 1/10 with the hook. When the agent simply updates its answer and moves on, it does not treat the correction as a learning event. T6 ("Better approach found") reached only 3/10 — positive optimizations are harder for the agent to classify as capture-worthy than failures or corrections.

**4. The hook causes zero false positives.**

Across 60 FP runs in both conditions, `use_skill` was never called. The hook's reminder asks the agent to *evaluate* whether learning-worthy content emerged — not to always invoke the skill. Routine queries (syntax questions, passing tests, approved PRs) produce no trigger even with the reminder injected.

**5. The mechanism is: reminder shifts attention, not behavior.**

The `<self-improvement-reminder>` does not instruct the agent to call `use_skill`. It asks the agent to *evaluate* whether the session produced learning-worthy content. The agent still decides. The hook works by ensuring this evaluation happens at the right moment, rather than relying on the agent to remember spontaneously.

```
User query
  + <self-improvement-reminder>       ← hook injects
  → LLM evaluates: "learning-worthy?"
  → if yes → use_skill("self-improvement")
  → skill loaded → structured capture
```

**6. Token cost is real but bounded.**

Treatment sessions used ~6× more tokens than control (48K vs 8K typical). This overhead is incurred once per session when the skill is invoked, not on every turn.

---

## Caveats

- **Single LLM**: results are specific to `minimax/minimax-m2.5`. Other models may show different trigger rates.
- **T6 low treatment rate**: "Better approach found" triggered only 3/10 with the hook. Positive-outcome optimizations may need a more targeted reminder to reliably trigger capture.
- **T5 unresponsive to hook**: "Outdated knowledge" shows no hook effect (1/10 in both conditions). The reminder's framing ("non-obvious solution", "workaround for unexpected behavior") does not match how the agent perceives a knowledge update.
- **Prerequisite fix**: the skill directory was renamed from `self-improving-agent-3.0.4` to `self-improvement` to match the `name:` field in `SKILL.md`. The `SkillLoader` validation pattern `^[a-z0-9]+(-[a-z0-9]+)*$` rejects dots, so versioned directory names are not supported.
