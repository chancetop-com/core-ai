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

Runs core-ai-cli with a real LLM against 6 trigger scenarios × 2 conditions (no hook / with hook), each repeated 3 times. Observes whether the agent calls `use_skill("self-improvement")`.

```bash
# Prerequisites: core-ai-cli built, API key in ~/.core-ai/agent.properties
./gradlew core-ai-cli:installDist
bash docs/skillwithhook/validate-self-improving-ablation.sh
```

The script creates two isolated temp workspaces, copies the `self-improvement` skill into both, adds `hooks.json` only to the treatment workspace, then pipes each query to the CLI as stdin:

```
printf '<query>\n/exit\n' | core-ai-cli --dangerously-skip-permissions --workspace <ws>
```

Output: per-scenario hit counts and overall trigger rates.

---

## Experimental Design

### Controlled Variable: Query

All 6 queries contain **implicit** learning signals — no instruction to capture learnings. Each matches one condition from the skill's description.

| ID | Trigger Condition | Query Signal |
|----|------------------|-------------|
| T1 | Command fails unexpectedly | Gradle proxy HTTPS resolution took 1 hour |
| T2 | User corrects agent | Wrong `@Transactional` advice given by agent |
| T3 | Capability gap | Agent didn't know Jacoco task ordering |
| T4 | External API fails | OpenAI 429 from client-per-request misuse |
| T5 | Knowledge outdated | `java.util.Date` flagged in code review |
| T6 | Better approach found | Centralized `ObjectMapper` bean saves 40 lines |

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

## Results (3 runs per scenario)

| ID | Trigger Condition | No Hook | With Hook |
|----|------------------|---------|-----------|
| T1 | Command fails unexpectedly | 0/3 | **3/3** |
| T2 | User corrects agent | 3/3 | 3/3 |
| T3 | Capability gap | 1/3 | **3/3** |
| T4 | External API fails | 0/3 | **3/3** |
| T5 | Outdated knowledge | 0/3 | 1/3 |
| T6 | Better approach found | 0/3 | **3/3** |
| **Total** | | **4/18 (22%)** | **16/18 (89%)** |

LLM: `minimax/minimax-m2.5` · Date: 2026-03-19

---

## Conclusions

**1. Hook raises trigger rate from 22% to 89%.**

Without the reminder, the agent completes tasks and stops. With the hook, it evaluates the session and calls `use_skill` in most cases. The effect is consistent across 3 repeated runs.

**2. Strong signals self-trigger without a hook (T2).**

"User corrects agent" (T2) triggered 3/3 even without the hook. When the signal is explicit enough — the agent recognizes it said something wrong — it captures the learning regardless. The hook is most valuable for subtle or positive-outcome signals (T1, T4, T6).

**3. The hook does not guarantee triggering (T5).**

"Outdated knowledge" (T5) triggered only 1/3 with the hook. The LLM tends to self-correct and move on when knowledge is simply updated, not treating it as a learning-worthy event. This is a known limitation of reminder-based approaches.

**4. The mechanism is: reminder shifts attention, not behavior.**

The `<self-improvement-reminder>` does not instruct the agent to call `use_skill`. It asks the agent to *evaluate* whether the session produced learning-worthy content. The agent still decides. The hook works by ensuring the agent performs this evaluation at the right moment, rather than relying on it to remember spontaneously.

```
User query
  + <self-improvement-reminder>       ← hook injects
  → LLM evaluates: "learning-worthy?"
  → if yes → use_skill("self-improvement")
  → skill loaded → structured capture
```

**5. Token cost is real but bounded.**

Treatment sessions used ~6× more tokens than control (48K vs 8K typical). This overhead is incurred once per session when the skill is invoked, not on every turn.

---

## Caveats

- **Single LLM**: results are specific to `minimax/minimax-m2.5`. Other models may show different trigger rates.
- **Single run = low confidence**: 3 runs per scenario reveals variance (T3, T5) but is not sufficient for statistical claims.
- **Prerequisite fix**: the skill directory was renamed from `self-improving-agent-3.0.4` to `self-improvement` to match the `name:` field in `SKILL.md`. The `SkillLoader` validation pattern `^[a-z0-9]+(-[a-z0-9]+)*$` rejects dots, so versioned directory names are not supported.
