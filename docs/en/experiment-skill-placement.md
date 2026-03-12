# Skill Placement Experiment

> Where should skill descriptions be placed for optimal LLM tool-call accuracy?

## Background

In an LLM Agent framework, skills (reusable prompt templates) need to be discoverable by the model so it can decide when to invoke them. There are two natural locations to place skill metadata:

- **Tool Description**: The `description` field in the OpenAI-compatible tool/function schema. Models are fine-tuned to read this during tool selection.
- **System Prompt**: The system message at the start of the conversation. Enjoys primacy bias (high attention weight at context start).

Each skill has two types of information:
- **What** — functional description (what the skill does)
- **When** — trigger conditions (when to use it, when NOT to use it)

This experiment tests which placement strategy yields the highest tool-call accuracy.

## Experiment Design

### Independent Variable: Placement Strategy (4 Groups)

| Group | Tool Description Content | System Prompt Content | Strategy |
|-------|-------------------------|----------------------|----------|
| A | skill name + what + when | `"You are a helpful assistant with access to tools."` | All in tool desc |
| B | `"Use a skill to accomplish specialized tasks."` | skill name + what + when | All in system prompt |
| C | skill name + what + when | skill name + what + when | Duplicated in both |
| D | skill name + **what** only | skill **when** (trigger conditions) only | What/When separation |

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

#### Summary

| Group | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|-------|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | 0.944 | 0.680 | 0.791 | 0.025 | 0.600 | 17 | 1 | 8 | 14 |
| B | 1.000 | 0.680 | 0.810 | 0.000 | 0.600 | 17 | 0 | 8 | 15 |
| **C** | **1.000** | **0.800** | **0.889** | **0.000** | **1.000** | **20** | **0** | **5** | **15** |
| D | 1.000 | 0.640 | 0.780 | 0.000 | 0.800 | 16 | 0 | 9 | 15 |

**Winner: Group C (F1=0.889)**

#### Confusion Matrix — Group A (all in tool desc)

```
                    Predicted
                  Trigger   No Trigger
Actual  Should     17 (TP)     8 (FN)
        Shouldn't   1 (FP)    14 (TN)
```

#### Confusion Matrix — Group B (all in system prompt)

```
                    Predicted
                  Trigger   No Trigger
Actual  Should     17 (TP)     8 (FN)
        Shouldn't   0 (FP)    15 (TN)
```

#### Confusion Matrix — Group C (duplicated in both)

```
                    Predicted
                  Trigger   No Trigger
Actual  Should     20 (TP)     5 (FN)
        Shouldn't   0 (FP)    15 (TN)
```

#### Confusion Matrix — Group D (what/when separation)

```
                    Predicted
                  Trigger   No Trigger
Actual  Should     16 (TP)     9 (FN)
        Shouldn't   0 (FP)    15 (TN)
```

#### Missed Cases Detail

| Group | Missed Positive/Ambiguous Queries | False Triggers |
|-------|----------------------------------|----------------|
| A | security vulnerabilities, SQL (x2), Docker deploy, CI/CD, refactor strategy | git log → git-commit |
| B | security vulnerabilities, optimize SQL, API docs, CI/CD, refactor strategy, perf analyze | none |
| C | README, CI/CD, refactor strategy, spaghetti code, perf analyze | none |
| D | security vulnerabilities, optimize SQL, Docker deploy, CI/CD, refactor (x2), perf (x2) | none |

### Claude Sonnet 4.6 (partial — cancelled during Group C)

| Group | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|-------|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | 1.000 | 0.520 | 0.684 | 0.000 | 0.600 | 13 | 0 | 12 | 15 |
| B | 1.000 | 0.720 | 0.837 | 0.000 | 1.000 | 18 | 0 | 7 | 15 |
| C | — | — | — | — | — | — | — | — | — |

## Key Findings

### 1. Group C (duplicated) wins for GPT-4.1-mini

Duplicating skill info in both tool description and system prompt gave the best results across all metrics. The redundancy reinforced the model's understanding rather than diluting attention.

### 2. Group A is the only one with false triggers

Putting all info in tool description led to the only false positive (FP=1): "Show me the git log" was incorrectly routed to git-commit. When skills are listed in the tool schema, the model is biased toward calling the tool.

### 3. Group D (what/when separation) performed worst

Against our initial hypothesis, separating "what" (tool desc) and "when" (system prompt) resulted in the lowest recall (0.640). The model appeared to hesitate when neither location had complete information.

### 4. Claude vs GPT: different attention patterns

Claude Sonnet showed much lower recall when skill info was only in tool description (Group A: 0.520), but improved significantly with system prompt (Group B: 0.720). This suggests **model-specific tuning matters** — the optimal placement may differ per model family.

### 5. Common hard cases across all groups

These queries consistently failed to trigger across most groups:

| Query | Expected Skill | Groups that missed |
|-------|---------------|-------------------|
| Refactor this class to use the strategy pattern | refactor | A, B, C, D |
| Create a CI/CD pipeline with GitHub Actions | deploy | A, B, C, D |
| Profile this method and find the bottleneck | perf-analyze | C, D |
| Why is this endpoint so slow? Analyze performance. | perf-analyze | B, D |

These may indicate that the skill descriptions need better keyword coverage, not a placement change.

## Limitations

1. **Token budget not controlled** — Group C has ~2x the total tokens of A/B/D. Cannot isolate whether the improvement is from duplication strategy or simply more information.
2. **Single tool only** — Real agents have multiple tools competing for selection. This experiment only tests "call or not call" on one tool.
3. **temperature=0 with majority vote** — Deterministic output makes 3 runs redundant. Should either use temperature>0 or run once.
4. **Small sample size** — 40 cases without statistical significance testing. Differences between groups may be noise.
5. **Ambiguous evaluation uses substring matching** — `"code-review,refactor".contains("review")` could false-match. Should use set-based comparison.
6. **No execution order randomization** — Fixed A→B→C→D order may introduce API-level bias (caching, rate limiting).

## Next Steps

- [ ] Fix ambiguous evaluation bug (substring → set matching)
- [ ] Add more models (GPT-5.2, minimax-m2.5) and complete Claude run
- [ ] Control token budget: pad shorter prompts or trim longer ones
- [ ] Add distractor tools (file_read, shell_exec) to simulate real multi-tool scenario
- [ ] Scale test: increase skill count to 20, 30, 50 to find crossover point
- [ ] Use temperature>0 with more runs for statistical significance
