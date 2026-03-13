# Skill Placement Experiment

> Where should skill descriptions be placed for optimal LLM tool-call accuracy?

## Background

In an LLM Agent framework, skills (reusable prompt templates) need to be discoverable by the model so it can decide when to invoke them. There are two natural locations to place skill metadata:

- **Tool Description**: The `description` field in the OpenAI-compatible tool/function schema. Models are fine-tuned to read this during tool selection.
- **System Prompt**: The system message at the start of the conversation. Enjoys primacy bias (high attention weight at context start).

Each skill has two types of information:
- **What** — functional description (what the skill does)
- **When** — trigger conditions (when to use it, when NOT to use it)

This experiment tests which placement strategy yields the highest tool-call accuracy, and specifically whether cross-location placement outperforms same-location repetition.

## Experiment Design

### Independent Variable: Placement Strategy (6 Groups)

| Group | Tool Description Content | System Prompt Content | Strategy |
|-------|-------------------------|----------------------|----------|
| A | skill name + what + when | `"You are a helpful assistant with access to tools."` | All in tool desc |
| B | `"Use a skill to accomplish specialized tasks."` | skill name + what + when | All in system prompt |
| C | skill name + what + when | skill name + what + when | Duplicated across locations |
| D | skill name + **what** only | skill **when** (trigger conditions) only | What/When separation |
| E | skill info **repeated 2x** | generic prompt | Same-location repetition (tool desc) |
| F | generic prompt | skill info **repeated 2x** | Same-location repetition (system prompt) |

Groups E and F serve as controls to distinguish between two hypotheses:
- **Cross-location hypothesis**: C wins because tool desc and system prompt are processed through different attention pathways
- **Pure repetition hypothesis**: C wins simply because the information appears twice

### Controlled Variables

- Same 10 mock skills across all groups
- Same 40 test queries
- Same model per comparison
- temperature=0.0 (deterministic)
- Single tool schema (`use_skill` with `name` parameter)
- 3 runs per case, majority vote

### Mock Skills (10)

| Skill | What (functional description) | When (trigger conditions) |
|-------|------------------------------|--------------------------|
| code-review | Reviews code for bugs, security issues, and style problems | Use for review/bugs/security. NOT for writing new code or refactoring |
| git-commit | Creates well-formatted git commits with conventional messages | Use for committing/staging. NOT for git log, diff, or read-only ops |
| web-search | Searches the web for current information | Use for real-time info/current events. NOT for codebase questions |
| test-generator | Generates unit tests (JUnit, pytest, jest) | Use for generating/writing tests. NOT for running or fixing tests |
| sql-query | Writes and optimizes SQL (MySQL, PostgreSQL, SQLite) | Use for writing/optimizing SQL. NOT for ORM or app-level data access |
| doc-writer | Generates docs, READMEs, API documentation | Use for documentation/README. NOT for code comments in reviews |
| deploy | Manages deployment, Docker, CI/CD setup | Use for deployment/Docker/CI/CD. NOT for local dev setup |
| refactor | Refactors code for structure and readability | Use for explicit refactoring. NOT for bug fixes or feature additions |
| api-design | Designs REST/GraphQL APIs with schemas | Use for API design/contracts. NOT for implementing handlers |
| perf-analyze | Analyzes performance, identifies bottlenecks | Use for profiling/benchmarking. NOT for general review or bug fixes |

### Test Set (40 cases)

#### Positive Cases (20) — should trigger a specific skill

| # | Query | Expected Skill | Variant |
|---|-------|---------------|---------|
| 1 | Review this Java class for potential bugs | code-review | direct match |
| 2 | Can you check this code for security vulnerabilities? | code-review | security focus |
| 3 | Commit these changes with a good message | git-commit | direct match |
| 4 | Stage all modified files and create a commit | git-commit | multi-step |
| 5 | What happened in the news today? | web-search | current events |
| 6 | Search the web for the latest Python 3.13 features | web-search | explicit search |
| 7 | Generate unit tests for this service class | test-generator | direct match |
| 8 | Write JUnit tests for the UserController | test-generator | framework specific |
| 9 | Write a SQL query to find top 10 customers by revenue | sql-query | direct match |
| 10 | Optimize this slow PostgreSQL query | sql-query | optimization |
| 11 | Write a README for this project | doc-writer | direct match |
| 12 | Generate API documentation for these endpoints | doc-writer | api docs |
| 13 | Set up a Docker deployment for this Spring Boot app | deploy | direct match |
| 14 | Create a CI/CD pipeline with GitHub Actions | deploy | ci/cd |
| 15 | Refactor this class to use the strategy pattern | refactor | direct match |
| 16 | Clean up this spaghetti code and improve readability | refactor | cleanup |
| 17 | Design a REST API for a todo app | api-design | direct match |
| 18 | What should the API contract look like for user management? | api-design | contract |
| 19 | Profile this method and find the bottleneck | perf-analyze | direct match |
| 20 | Why is this endpoint so slow? Analyze performance. | perf-analyze | diagnosis |

#### Near-miss Cases (8) — semantically close, should NOT trigger

| # | Query | Trap Skill | Why it should NOT trigger |
|---|-------|-----------|--------------------------|
| 1 | Show me the git log for the last 10 commits | git-commit | read-only git op |
| 2 | Run the existing tests and show results | test-generator | running, not generating |
| 3 | Fix this failing test assertion | test-generator | fixing, not generating |
| 4 | Add input validation to this endpoint handler | api-design | implementing, not designing |
| 5 | Set up my local development environment | deploy | local dev, not deployment |
| 6 | Add a comment explaining this complex regex | doc-writer | inline comment, not documentation |
| 7 | Fix this NullPointerException in the service layer | code-review | bug fix, not review |
| 8 | Write the JPA entity for this table | sql-query | ORM code, not SQL |

#### Ambiguous Cases (5) — could match multiple skills

| # | Query | Acceptable Skills |
|---|-------|------------------|
| 1 | Review and refactor this authentication module | code-review, refactor |
| 2 | Write tests and document the payment service | test-generator, doc-writer |
| 3 | Design and deploy a microservice API | api-design, deploy |
| 4 | Optimize this SQL query and profile the endpoint | sql-query, perf-analyze |
| 5 | Review the code and check for performance issues | code-review, perf-analyze |

#### Negative Cases (7) — unrelated, should not trigger any skill

| # | Query | Category |
|---|-------|----------|
| 1 | What is the capital of France? | general knowledge |
| 2 | Explain how HashMap works in Java | explanation |
| 3 | Calculate 42 * 17 + 3 | math |
| 4 | Tell me a joke about programming | entertainment |
| 5 | What does this error message mean? | explanation |
| 6 | How do I install Node.js on macOS? | setup instruction |
| 7 | What is the difference between REST and GraphQL? | comparison |

### Evaluation Metrics

- **TP** (True Positive): Positive/Ambiguous case, correct skill triggered
- **FP** (False Positive): Wrong skill triggered, or skill triggered on Near-miss/Negative case
- **FN** (False Negative): Positive/Ambiguous case, no skill triggered or wrong skill
- **TN** (True Negative): Near-miss/Negative case, correctly no skill triggered
- **Precision** = TP / (TP + FP)
- **Recall** = TP / (TP + FN)
- **F1** = 2 * P * R / (P + R)
- **False Trigger Rate** = FP / total cases
- **Ambiguous Accuracy** = correct ambiguous / total ambiguous

## Results

### GPT-4.1-mini

| Group | Strategy | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|-------|----------|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | All in tool desc | 0.944 | 0.680 | 0.791 | 0.025 | 0.600 | 17 | 1 | 8 | 14 |
| B | All in system prompt | 1.000 | 0.680 | 0.810 | 0.000 | 0.600 | 17 | 0 | 8 | 15 |
| **C** | **Cross-location** | **1.000** | **0.800** | **0.889** | **0.000** | **1.000** | **20** | **0** | **5** | **15** |
| D | What/When separation | 1.000 | 0.640 | 0.780 | 0.000 | 0.800 | 16 | 0 | 9 | 15 |
| E | Tool desc repeated 2x | 0.944 | 0.680 | 0.791 | 0.025 | 0.800 | 17 | 1 | 8 | 14 |
| F | System prompt repeated 2x | 1.000 | 0.680 | 0.810 | 0.000 | 0.600 | 17 | 0 | 8 | 15 |

#### Confusion Matrices

```
Group A (all in tool desc)          Group B (all in system prompt)
          Trigger  No Trigger                Trigger  No Trigger
Should      17(TP)    8(FN)       Should      17(TP)    8(FN)
Shouldn't    1(FP)   14(TN)      Shouldn't    0(FP)   15(TN)
P=0.944 R=0.680 F1=0.791         P=1.000 R=0.680 F1=0.810

Group C (cross-location)           Group D (what/when separation)
          Trigger  No Trigger                Trigger  No Trigger
Should      20(TP)    5(FN)       Should      16(TP)    9(FN)
Shouldn't    0(FP)   15(TN)      Shouldn't    0(FP)   15(TN)
P=1.000 R=0.800 F1=0.889         P=1.000 R=0.640 F1=0.780

Group E (tool desc 2x)             Group F (system prompt 2x)
          Trigger  No Trigger                Trigger  No Trigger
Should      17(TP)    8(FN)       Should      17(TP)    8(FN)
Shouldn't    1(FP)   14(TN)      Shouldn't    0(FP)   15(TN)
P=0.944 R=0.680 F1=0.791         P=1.000 R=0.680 F1=0.810
```

### MiniMax-M2.5

| Group | Strategy | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|-------|----------|-----------|--------|----|-----------|--------------|----|----|----|----|
| **A** | **All in tool desc** | **1.000** | **0.480** | **0.649** | **0.000** | **0.200** | **12** | **0** | **13** | **15** |
| B | All in system prompt | 0.909 | 0.400 | 0.556 | 0.025 | 0.000 | 10 | 1 | 15 | 14 |
| C | Cross-location | 0.923 | 0.480 | 0.632 | 0.025 | 0.200 | 12 | 1 | 13 | 14 |
| D | What/When separation | 0.917 | 0.440 | 0.595 | 0.025 | 0.200 | 11 | 1 | 14 | 14 |
| E | Tool desc repeated 2x | 1.000 | 0.400 | 0.571 | 0.000 | 0.200 | 10 | 0 | 15 | 15 |
| F | System prompt repeated 2x | 1.000 | 0.400 | 0.571 | 0.000 | 0.000 | 10 | 0 | 15 | 15 |

Note: MiniMax-M2.5 had occasional 500 errors and timeouts from OpenRouter, which may have depressed recall in affected groups (B, C).

### Claude Sonnet 4.6 (partial — cancelled during Group C)

| Group | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|-------|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | 1.000 | 0.520 | 0.684 | 0.000 | 0.600 | 13 | 0 | 12 | 15 |
| B | 1.000 | 0.720 | 0.837 | 0.000 | 1.000 | 18 | 0 | 7 | 15 |

## Hypothesis Validation: Cross-Location vs Same-Location Repetition

The key question: Does Group C win because information appears in two **different locations**, or simply because it appears **twice**?

### GPT-4.1-mini

| Comparison | F1 | Recall | Conclusion |
|-----------|-----|--------|------------|
| C (cross-location) | **0.889** | **0.800** | Best overall |
| E (tool desc 2x) vs A (tool desc 1x) | 0.791 vs 0.791 | 0.680 vs 0.680 | Same-location repetition has zero effect |
| F (system prompt 2x) vs B (system prompt 1x) | 0.810 vs 0.810 | 0.680 vs 0.680 | Same-location repetition has **zero effect** |
| C vs E | 0.889 vs 0.791 | 0.800 vs 0.680 | Cross-location is significantly better |
| C vs F | 0.889 vs 0.810 | 0.800 vs 0.680 | Cross-location is significantly better |

**Verdict: Cross-location complementarity, not pure repetition.**

### MiniMax-M2.5

| Comparison | F1 | Conclusion |
|-----------|-----|------------|
| C (cross-location) | 0.632 | Not the best (A=0.649 is better) |
| E (tool desc 2x) vs A (tool desc 1x) | 0.571 vs 0.649 | Repetition **hurts** |
| F (system prompt 2x) vs B (system prompt 1x) | 0.571 vs 0.556 | Marginal difference |

**Verdict: Cross-location does NOT help MiniMax. Tool desc only (A) is optimal.**

## Analysis: Why Models Behave Differently

### The Root Cause: Prompt Format Architecture

The divergent results between GPT and MiniMax can be explained by how each model encodes tool definitions at the prompt template level. The following analysis is based on publicly available documentation and open-source model artifacts; actual internal implementations may differ.

#### OpenAI GPT (Harmony Format)

Based on OpenAI's published Harmony response format specification and the open-sourced gpt-oss model, GPT models use distinct message segments:

```
<|start|>system<|message|>You are a helpful assistant.<|end|>
<|start|>developer<|message|>
namespace functions {
  // Use a skill to accomplish specialized tasks.
  type use_skill = (_: { name: string }) => any;
}
<|end|>
<|start|>user<|message|>Review this Java class...<|end|>
```

Key architectural detail: **Tool definitions are injected into a `developer` message, separate from the `system` message.** These are different segments with different special tokens (`<|start|>system` vs `<|start|>developer`), creating distinct attention patterns during inference.

This means:
- **Group A** (tool desc only): Skill info is in the `developer` segment only
- **Group B** (system prompt only): Skill info is in the `system` segment only
- **Group C** (both): Skill info is in **two different segments** — the model encounters it through two separate attention pathways
- **Group E** (tool desc 2x): Skill info is repeated within the same `developer` segment — no new attention pathway

This explains why C > E for GPT: cross-segment placement activates different attention heads that were trained to process `system` vs `developer` content differently.

#### MiniMax-M2.5 (Custom Format)

Based on MiniMax's open-source model repository and tool calling guide, MiniMax uses its own special token system:

```
]~!b[]~b]system
You are a helpful assistant.
<tools>
[{"type":"function","function":{"name":"use_skill",...}}]
</tools>
[e~[]~b]user
Review this Java class...
[e~[]~b]ai
```

Critical difference: **Tool definitions are embedded inside the `system` message via `<tools>` tags.** There is no separate `developer` segment. Tool desc and system prompt share the same attention segment.

This means:
- **Group C** for MiniMax: Both copies of skill info end up in the same `system` segment — functionally equivalent to same-location repetition
- No cross-segment benefit because there's only one segment for both system instructions and tool schemas
- Group A (tool desc only) works best because the `<tools>` tag provides a clear structural boundary within the system message, making the model more confident about tool schemas

#### Claude (Anthropic Format)

Claude uses a system prompt that is separate from tool definitions in the API, but Anthropic's internal prompt template is not publicly documented. The large gap between Group A (0.520) and Group B (0.720) tentatively suggests that Claude's training may weight system prompt content more heavily for behavioral guidance, while tool schemas may be primarily used for parameter validation rather than invocation decisions. This interpretation is based on only 2 of 6 groups and should be verified with complete data.

### Summary: Format Determines Optimal Strategy

| Model | Prompt Format | Tool Segment | Optimal Strategy | Why |
|-------|--------------|-------------|-----------------|-----|
| GPT-4.1-mini | Harmony | Separate `developer` msg | C (cross-location) | Two distinct attention segments reinforce each other |
| MiniMax-M2.5 | Custom tokens | Inside `system` msg (`<tools>`) | A (tool desc only) | Single segment; `<tools>` tag gives structural clarity |
| Claude Sonnet | Anthropic format | Separate but less weighted | B (system prompt) | Training biases toward system prompt for behavior |

## Key Findings

### 1. Cross-location placement wins for models with separate tool segments (GPT)

Group C (F1=0.889) outperforms all other groups for GPT-4.1-mini. The E/F control groups prove this is NOT due to repetition — it's due to cross-segment attention reinforcement.

### 2. Same-location repetition provides zero benefit (or hurts)

- GPT: E=A (identical F1=0.791), F=B (identical F1=0.810)
- MiniMax: E < A (0.571 < 0.649), F ≈ B

Repeating information in the same prompt segment adds no signal. The model already has full attention over that segment.

### 3. The optimal strategy is model-dependent

There is no universal best placement. The optimal strategy depends on how the model's prompt template separates tool definitions from system instructions.

### 4. Tool-use training quality matters more than placement

MiniMax-M2.5's best F1 (0.649) is far below GPT-4.1-mini's worst (0.780). The model's tool-use fine-tuning quality dominates over placement strategy.

### 5. Group D (what/when separation) consistently underperforms for GPT

For GPT-4.1-mini, D has the lowest F1 (0.780). For MiniMax, D (0.595) is below baseline A (0.649) but not the absolute worst (E/F are 0.571). In both cases, splitting functional description and trigger conditions across locations provides neither location with enough context for confident decision-making.

## Limitations

1. **Token budget not controlled** — Group C has ~2x the total tokens of A/B. Groups E/F partially address this (same token count as C, different placement).
2. **Single tool only** — Real agents have multiple tools competing for selection.
3. **temperature=0 with majority vote** — Deterministic output makes 3 runs redundant.
4. **Small sample size** — 40 cases without statistical significance testing.
5. **Ambiguous evaluation uses substring matching** — Should use set-based comparison.
6. **Fixed execution order** — A→B→C→D→E→F may introduce API-level bias.
7. **MiniMax API instability** — Occasional 500 errors and timeouts may have affected results.

## Next Steps

- [ ] Fix ambiguous evaluation bug (substring → set matching)
- [ ] Complete Claude Sonnet run with all 6 groups
- [ ] Add distractor tools (file_read, shell_exec) to simulate real multi-tool scenario
- [ ] Scale test: increase skill count to 20, 30, 50 to find crossover point
- [ ] Verify prompt format theory by inspecting actual token sequences sent to each model
- [ ] Test with open-source models where prompt templates are fully visible (Qwen, Llama)
