package ai.core.cli.agent;

import ai.core.agent.ExecutionContext;
import ai.core.agent.budget.BudgetBinding;
import ai.core.agent.budget.BudgetLevel;
import ai.core.agent.budget.RunBudget;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.InlineImagePruner;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refreshes the dynamic budget header in messages[0] and applies the token-aware
 * inline image budget. The lifecycle instance is shared by the main agent and its
 * subagents, so refresh state is keyed by execution context rather than held in a
 * single field.
 *
 * @author stephen
 */
public final class RunBudgetLifecycle extends AbstractLifecycle {
    private static String bucket(RunBudget budget) {
        if (budget == null) return "";
        var sb = new StringBuilder(8);
        appendPercentBucket(sb, budget.tokens() == null ? -1 : percentRemaining(
                budget.tokens().limitTokens(), budget.tokens().remainingTokens()));
        appendPercentBucket(sb, budget.cost() == null ? -1 : percentRemaining(
                budget.cost().limitMicros(), budget.cost().remainingMicros()));
        return sb.toString();
    }

    private static void appendPercentBucket(StringBuilder sb, int percent) {
        char bucket;
        if (percent < 0) bucket = '-';
        else if (percent <= 2) bucket = '0';
        else if (percent <= 5) bucket = '1';
        else if (percent <= 10) bucket = '2';
        else if (percent <= 25) bucket = '3';
        else bucket = '4';
        sb.append(bucket);
    }

    private static int percentRemaining(long limit, long remaining) {
        if (limit <= 0) return 100;
        return (int) Math.max(0, Math.min(100, remaining * 100 / limit));
    }

    private static String key(ExecutionContext context) {
        return context.getSessionId() + ":" + System.identityHashCode(context);
    }

    private static void replaceSystemMessage(CompletionRequest request, String text) {
        var message = Message.of(RoleType.SYSTEM, text);
        if (request.messages instanceof ArrayList) {
            request.messages.set(0, message);
        } else {
            var updated = new ArrayList<>(request.messages);
            updated.set(0, message);
            request.messages = updated;
        }
    }

    private final RunBudget budget;
    private final Map<String, RefreshState> states = new ConcurrentHashMap<>();

    public RunBudgetLifecycle(RunBudget budget) {
        this.budget = budget;
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        if (request == null || request.messages == null || request.messages.isEmpty()) return;
        var system = request.messages.getFirst();
        if (system.role != RoleType.SYSTEM) return;

        var state = states.computeIfAbsent(key(context), ignored -> new RefreshState());
        var current = system.getTextContent();
        var hasBudget = budget != null;
        if (shouldRefresh(state) || hasBudget != RunBudgetSection.containsBudgetBlock(current)) {
            refresh(request, state);
        }
        applyImageBudget(request, state);
    }

    private void refresh(CompletionRequest request, RefreshState state) {
        var current = request.messages.getFirst().getTextContent();
        var block = RunBudgetSection.render(budget);
        var next = RunBudgetSection.replace(current, block);
        if (!next.equals(current)) {
            replaceSystemMessage(request, next);
        }
        state.initialized = true;
        state.lastLevel = budget == null ? null : budget.level();
        state.lastBinding = budget == null ? null : budget.binding();
        state.lastBucket = bucket(budget);
    }

    private boolean shouldRefresh(RefreshState state) {
        if (!state.initialized) return true;
        if (budget == null) return false;
        var level = budget.level();
        var binding = budget.binding();
        if (level != state.lastLevel || binding != state.lastBinding) return true;
        return !bucket(budget).equals(state.lastBucket);
    }

    private void applyImageBudget(CompletionRequest request, RefreshState state) {
        if (budget == null || budget.tokens() == null) return;
        var level = budget.level();
        if (state.lastImageLevel == level) return;
        int maxImages = switch (level) {
            case NORMAL -> 16;
            case CONSERVE -> 8;
            case URGENT -> 4;
            case FINAL -> 1;
            case EXPIRED -> 0;
        };
        long maxBytes = switch (level) {
            case NORMAL -> 12L * 1024 * 1024;
            case CONSERVE -> 6L * 1024 * 1024;
            case URGENT -> 3L * 1024 * 1024;
            case FINAL, EXPIRED -> 1L * 1024 * 1024;
        };
        var pruned = InlineImagePruner.prune(request.messages, maxImages, maxBytes);
        if (pruned.prunedCount() > 0) {
            if (request.messages instanceof ArrayList) {
                request.messages.clear();
                request.messages.addAll(pruned.messages());
            } else {
                request.messages = pruned.messages();
            }
        }
        state.lastImageLevel = level;
    }

    private static final class RefreshState {
        private boolean initialized;
        private BudgetLevel lastLevel;
        private BudgetBinding lastBinding;
        private String lastBucket = "";
        private BudgetLevel lastImageLevel;
    }
}
