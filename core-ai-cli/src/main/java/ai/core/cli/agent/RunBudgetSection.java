package ai.core.cli.agent;

import ai.core.agent.budget.BudgetBinding;
import ai.core.agent.budget.BudgetLevel;
import ai.core.agent.budget.CostBudget;
import ai.core.agent.budget.RunBudget;
import ai.core.agent.budget.TimeBudget;
import ai.core.agent.budget.TokenBudget;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders and replaces the dynamic &lt;run_budget&gt; block. The static policy is
 * injected separately by {@link RunBudgetPrompt}; this class only emits the header
 * with current numbers and the one-line directive.
 *
 * @author stephen
 */
public final class RunBudgetSection {
    private static final Pattern BLOCK = Pattern.compile(
            "(?s)<!-- core-ai:run-budget:begin -->.*?<!-- core-ai:run-budget:end -->");
    private static final Pattern LEGACY_TIME_BLOCK = Pattern.compile(
            "(?s)<!-- core-ai:time-budget:begin -->.*?<!-- core-ai:time-budget:end -->");
    private static final String BEGIN = "<!-- core-ai:run-budget:begin -->";
    private static final String END = "<!-- core-ai:run-budget:end -->";

    public static String render(RunBudget budget) {
        if (budget == null) return null;
        var sb = new StringBuilder(384);
        sb.append(BEGIN + "\n<run_budget>\n");
        var level = budget.level();
        var binding = budget.binding();
        sb.append(String.format("Status: %s (binding: %s)%nDirective: %s%n", level,
                binding == null ? "none" : binding.name().toLowerCase(Locale.ROOT), directive(level, binding)));
        appendTime(sb, budget.time());
        appendTokens(sb, budget.tokens());
        appendCost(sb, budget.cost());
        appendClock(sb, budget.time());
        sb.append("</run_budget>\n" + END);
        return sb.toString();
    }

    private static void appendTime(StringBuilder sb, TimeBudget time) {
        if (time == null) return;
        sb.append(String.format("Time: %ds of %ds left. Verification reserve: %ds. Soft deadline: %ds from now.%n",
                time.remainingSeconds(), time.totalMillis() / 1000, time.reserveMillis() / 1000,
                time.softRemainingMillis() / 1000));
    }

    private static void appendTokens(StringBuilder sb, TokenBudget tokens) {
        if (tokens == null) return;
        sb.append(String.format("Tokens: %,d of %,d used; %,d left. Final-answer reserve: %,d tokens.%n",
                tokens.spentTokens(), tokens.limitTokens(), tokens.remainingTokens(), tokens.reserveTokens()));
    }

    private static void appendCost(StringBuilder sb, CostBudget cost) {
        if (cost == null) return;
        sb.append(String.format("Cost: $%.2f of $%.2f used; $%.2f left. Final-answer reserve: $%.2f.%n",
                cost.spentMicros() / (double) CostBudget.MICROS_PER_USD,
                cost.limitMicros() / (double) CostBudget.MICROS_PER_USD,
                cost.remainingMicros() / (double) CostBudget.MICROS_PER_USD,
                cost.reserveMicros() / (double) CostBudget.MICROS_PER_USD));
    }

    private static void appendClock(StringBuilder sb, TimeBudget time) {
        if (time == null) return;
        sb.append(String.format("Started (UTC): %s.%nHard deadline (UTC): %s.%n",
                time.startedAt(), time.deadline()));
    }

    private static String directive(BudgetLevel level, BudgetBinding binding) {
        if (level == null) return "Work normally, but keep the deliverable ahead of polish.";
        return switch (level) {
            case NORMAL -> "Work normally, but keep the deliverable ahead of polish.";
            case CONSERVE -> switch (binding) {
                case TOKENS -> "Stop re-reading. Use save_to_file for large results and write the deliverable in one call.";
                case COST -> "Stop non-essential calls; no new media generation unless it is the deliverable.";
                case TIME -> "Stop exploring. Complete the required files and verify them now.";
                case null -> "Stop exploring. Complete the required files and verify them now.";
            };
            case URGENT -> switch (binding) {
                case TOKENS -> "Only finish the deliverable. Do not fetch/read more context unless it is required to write the file.";
                case COST -> "Only finish the deliverable. No media, no subagents, no expensive tools.";
                case TIME -> "Only finish and verify the required deliverable. No new installs, no new subagents.";
                case null -> "Only finish and verify the required deliverable. No new installs, no new subagents.";
            };
            case FINAL -> switch (binding) {
                case TOKENS -> "Answer now; if a required file is still missing, write only that file, then answer.";
                case COST -> "Answer now; no new paid calls.";
                case TIME -> "Stop tool use after at most one short check. Answer now with the current state.";
                case null -> "Stop tool use after at most one short check. Answer now with the current state.";
            };
            case EXPIRED -> "The budget is exhausted. Do not call tools. Answer with the current state.";
        };
    }

    public static String replace(String systemText, String block) {
        var text = systemText == null ? "" : LEGACY_TIME_BLOCK.matcher(systemText).replaceAll("");
        var matcher = BLOCK.matcher(text);
        if (block == null) return matcher.replaceAll("").strip();
        if (matcher.find()) {
            return matcher.replaceAll(Matcher.quoteReplacement(block));
        }
        return text.isBlank() ? block : text + "\n\n" + block;
    }

    public static boolean containsBudgetBlock(String text) {
        return text != null && BLOCK.matcher(text).find();
    }

    private RunBudgetSection() {
    }
}
