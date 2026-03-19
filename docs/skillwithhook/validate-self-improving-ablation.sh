#!/bin/bash
# Ablation validation: self-improving-agent with and without UserPromptSubmit hook.
#
# Two groups of scenarios:
#   True Positive (T1-T6): queries that SHOULD trigger use_skill
#   False Positive (FP1-FP6): routine queries that should NOT trigger use_skill
#
# Both control (no hook) and treatment (with hook) receive the identical query.
# Expected:
#   T-group  — control: low trigger rate, treatment: high trigger rate
#   FP-group — both conditions: low trigger rate (hook must not over-trigger)
#
# Trigger conditions (T-group):
#   T1 - Command/operation fails unexpectedly
#   T2 - User corrects the agent
#   T3 - User requests a non-existent capability
#   T4 - External API/tool fails
#   T5 - Agent realizes its knowledge is outdated
#   T6 - Better approach discovered for a recurring task
#
# False positive cases (FP-group) — outside skill scope:
#   FP1 - Basic syntax question with a single definitive answer
#   FP2 - Feature completed successfully as expected
#   FP3 - Factual question with a fixed numerical answer
#   FP4 - Tests pass normally
#   FP5 - Standard PR review, nothing unusual
#   FP6 - Routine configuration following documentation

set -e

CLI="./build/core-ai-cli/install/core-ai-cli/bin/core-ai-cli"
SKILL_SRC=".core-ai/skills/self-improvement"
TRUE_POSITIVE_IDS="T1 T2 T3 T4 T5 T6"
FALSE_POSITIVE_IDS="FP1 FP2 FP3 FP4 FP5 FP6"
RUNS=10

get_query() {
    case "$1" in
    # ── True positive scenarios ────────────────────────────────────────────────
    T1) echo "The gradle build kept failing with 'Could not resolve com.fasterxml.jackson.core:jackson-databind:2.15.0'. After an hour of debugging I found the issue: our corporate proxy requires HTTPS but Gradle was falling back to HTTP for the maven central mirror. Adding 'allowInsecureProtocol = false' and updating the repo URL to HTTPS fixed it." ;;
    T2) echo "You told me to use @Transactional on the service method to fix the lazy loading issue. Actually that made it worse — the transaction boundary was already set at the controller level and adding another one caused the session to be closed too early. The real fix was to use @Transactional(propagation = Propagation.REQUIRES_NEW) on the specific repository call." ;;
    T3) echo "I wanted to run the tests with coverage report and fail if coverage drops below 80%. I asked the agent to set this up but it didn't know that Gradle's jacoco plugin needs both jacocoTestReport and jacocoTestCoverageVerification tasks, and that you must call jacocoTestReport before the verification task or it reads stale data. Had to figure this out from the Gradle docs." ;;
    T4) echo "We were calling the OpenAI API and started getting 429 rate limit errors in production even though we were well within our tier limits. Turned out the issue was that we were creating a new OpenAI client instance per request instead of reusing a singleton — each instance maintained its own connection pool and rate limit counter, causing us to exceed the per-connection limits." ;;
    T5) echo "I was following the advice to use new Date() for timestamps in Java, but our code review flagged it as outdated. Turns out since Java 8, the recommended approach is java.time.Instant.now() or LocalDateTime.now() from the java.time package. The old java.util.Date class is considered legacy and has thread-safety and timezone handling issues." ;;
    T6) echo "We used to manually write Jackson ObjectMapper configuration in every service class. After refactoring, I realized we could register a single @Bean ObjectMapper in a Spring @Configuration class with all the standard settings (fail on unknown properties, snake case naming, JSR310 datetime support) and inject it everywhere. Cut 40 lines of boilerplate per service." ;;
    # ── False positive scenarios ───────────────────────────────────────────────
    FP1) echo "What is the syntax for a Java enhanced for loop?" ;;
    FP2) echo "Added the user profile page — implemented the GET /users/{id} endpoint, wrote the service layer, and added unit tests. All 12 tests pass and the feature is working as expected." ;;
    FP3) echo "How many elements can a Java int array hold at most?" ;;
    FP4) echo "Ran the full test suite after the refactoring. All 247 tests pass, build is green." ;;
    FP5) echo "Reviewed the PR for the payment module. The code looks clean, follows our conventions, test coverage is at 85%, and the logic matches the spec. Approved it." ;;
    FP6) echo "Set up logback.xml for the new service following the team template — added console and file appenders, set root level to INFO, and configured the package-level DEBUG for our domain classes. Everything logging as expected." ;;
    esac
}

get_label() {
    case "$1" in
    T1)  echo "[TP] Command fails unexpectedly (Gradle proxy)" ;;
    T2)  echo "[TP] User corrects agent (wrong @Transactional)" ;;
    T3)  echo "[TP] Capability gap (Jacoco coverage threshold)" ;;
    T4)  echo "[TP] External API fails (OpenAI 429 misuse)" ;;
    T5)  echo "[TP] Outdated knowledge (java.util.Date)" ;;
    T6)  echo "[TP] Better approach found (ObjectMapper bean)" ;;
    FP1) echo "[FP] Routine syntax question (enhanced for loop)" ;;
    FP2) echo "[FP] Feature completed normally" ;;
    FP3) echo "[FP] Factual language question (int array max size)" ;;
    FP4) echo "[FP] Tests pass, build green" ;;
    FP5) echo "[FP] Standard PR review, approved" ;;
    FP6) echo "[FP] Routine config (logback setup)" ;;
    esac
}

# ── Helpers ───────────────────────────────────────────────────────────────────

setup_workspace() {
    local dir="$1"
    local with_hook="$2"
    mkdir -p "$dir/.core-ai/skills"
    cp -r "$SKILL_SRC" "$dir/.core-ai/skills/"
    chmod +x "$dir/.core-ai/skills/self-improvement/scripts/"*.sh

    if [ "$with_hook" = "true" ]; then
        cat > "$dir/.core-ai/hooks.json" << 'HOOKEOF'
{
  "hooks": {
    "UserPromptSubmit": [{
      "matcher": "",
      "hooks": [{ "type": "command", "command": ".core-ai/skills/self-improvement/scripts/activator.sh" }]
    }]
  }
}
HOOKEOF
    fi
}

strip_ansi() {
    sed 's/\x1B\[[0-9;]*[mKJHf]//g; s/\x1B\[[0-9]*[ABCD]//g; s/\x1B(B//g; s/\r//g'
}

run_query() {
    local workspace="$1"
    local query="$2"
    local output_file="$3"

    printf '%s\n/exit\n' "$query" | \
        "$CLI" \
            --dangerously-skip-permissions \
            --workspace "$workspace" \
            > "$output_file" 2>&1
}

check_trigger() {
    local output_file="$1"
    if strip_ansi < "$output_file" | grep -q "use_skill"; then
        echo "✓"
    else
        echo "✗"
    fi
}

print_group() {
    local group_ids="$1"
    local group_label="$2"

    echo ""
    echo "  $group_label"
    printf "  %-6s  %-44s  %-16s  %-16s\n" "ID" "Scenario" "No Hook" "With Hook"
    echo "  ──────  ────────────────────────────────────────  ────────────────  ────────────────"

    local group_ctrl=0
    local group_trtm=0
    local group_n=0

    for id in $group_ids; do
        local label ctrl_hits trtm_hits
        label=$(get_label "$id")
        ctrl_hits=0
        trtm_hits=0

        for run in $(seq 1 $RUNS); do
            ctrl_out="$TMPDIR_BASE/${id}_ctrl_r${run}.txt"
            trtm_out="$TMPDIR_BASE/${id}_trtm_r${run}.txt"
            [ "$(check_trigger "$ctrl_out")" = "✓" ] && ctrl_hits=$((ctrl_hits + 1))
            [ "$(check_trigger "$trtm_out")" = "✓" ] && trtm_hits=$((trtm_hits + 1))
        done

        group_ctrl=$((group_ctrl + ctrl_hits))
        group_trtm=$((group_trtm + trtm_hits))
        group_n=$((group_n + 1))

        printf "  %-6s  %-44s  %d/%-14d  %d/%d\n" \
            "$id" "${label#*] }" "$ctrl_hits" "$RUNS" "$trtm_hits" "$RUNS"
    done

    local total=$((group_n * RUNS))
    echo ""
    printf "  %-52s  %d/%-14d  %d/%d\n" \
        "Group total" "$group_ctrl" "$total" "$group_trtm" "$total"
}

# ── Setup ─────────────────────────────────────────────────────────────────────

CONTROL_WS=$(mktemp -d)
TREATMENT_WS=$(mktemp -d)
TMPDIR_BASE=$(mktemp -d)

setup_workspace "$CONTROL_WS"   "false"
setup_workspace "$TREATMENT_WS" "true"

trap "rm -rf '$CONTROL_WS' '$TREATMENT_WS' '$TMPDIR_BASE'" EXIT

# ── Run all scenarios ─────────────────────────────────────────────────────────

ALL_IDS="$TRUE_POSITIVE_IDS $FALSE_POSITIVE_IDS"

echo ""
echo "Running 12 scenarios × 2 conditions × ${RUNS} runs  (${RUNS} TP + ${RUNS} FP per condition)"
echo "LLM: minimax/minimax-m2.5  |  Skill: self-improvement"
echo ""

for id in $ALL_IDS; do
    label=$(get_label "$id")
    query=$(get_query "$id")
    printf "  [%s] %s\n" "$id" "$label"

    for run in $(seq 1 $RUNS); do
        ctrl_out="$TMPDIR_BASE/${id}_ctrl_r${run}.txt"
        trtm_out="$TMPDIR_BASE/${id}_trtm_r${run}.txt"
        printf "       run %d:" "$run"
        run_query "$CONTROL_WS"   "$query" "$ctrl_out" ; printf " ctrl"
        run_query "$TREATMENT_WS" "$query" "$trtm_out" ; printf " treat"
        echo ""
    done
done

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  RESULTS  (hits / $RUNS runs)   ✓ = use_skill called   ✗ = not called"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

print_group "$TRUE_POSITIVE_IDS"  "True Positive  — should trigger  (expected: low control, high treatment)"
print_group "$FALSE_POSITIVE_IDS" "False Positive — must NOT trigger (expected: low in both conditions)"

echo ""
echo "  Raw outputs: $TMPDIR_BASE/"
