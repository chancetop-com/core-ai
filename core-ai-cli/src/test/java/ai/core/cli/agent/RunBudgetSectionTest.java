package ai.core.cli.agent;

import ai.core.agent.ExecutionContext;
import ai.core.agent.budget.RunBudget;
import ai.core.agent.budget.TimeBudget;
import ai.core.agent.budget.TokenEstimator;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class RunBudgetSectionTest {
    private static RunBudget timeBudget(AtomicLong clock) {
        var time = TimeBudget.ofSeconds(900, 135L, 0, Instant.parse("2026-10-21T14:00:00Z"), clock::get);
        return new RunBudget(time, null, null, new TokenEstimator(1.0), 0);
    }

    @Test
    void timeBlockIsStableWithinOneLevel() {
        var clock = new AtomicLong();
        var budget = timeBudget(clock);
        var first = RunBudgetSection.render(budget);
        clock.set(100_000_000_000L);
        var second = RunBudgetSection.render(budget);

        assertEquals(first, second);
        assertTrue(first.contains("Status: NORMAL"));
        assertTrue(first.contains("Budget: 900s total. Final-answer reserve: 135s."));
        assertTrue(first.contains("Hard stop (UTC): 2026-10-21T14:15:00Z"));
        assertFalse(first.contains("Soft deadline"));
        assertFalse(first.contains("Time:"));
    }

    @Test
    void timeBlockChangesOnlyAtLevelBoundaries() {
        var clock = new AtomicLong();
        var budget = timeBudget(clock);
        var normal = RunBudgetSection.render(budget);

        clock.set(500_000_000_000L);
        var conserve = RunBudgetSection.render(budget);
        assertTrue(conserve.contains("Status: CONSERVE"));
        assertNotEquals(normal, conserve);

        clock.set(800_000_000_000L);
        var fin = RunBudgetSection.render(budget);
        assertTrue(fin.contains("Status: FINAL"));
        assertNotEquals(conserve, fin);
        assertTrue(fin.contains("Hard stop (UTC): 2026-10-21T14:15:00Z"));
    }

    @Test
    void lifecycleKeepsTheSameSystemMessageWithinOneLevel() {
        var clock = new AtomicLong();
        var time = TimeBudget.ofSeconds(900, 135L, 0, Instant.EPOCH, clock::get);
        var budget = new RunBudget(time, null, null, new TokenEstimator(1.0), 0);
        var lifecycle = new RunBudgetLifecycle(budget);
        var context = ExecutionContext.builder().runBudget(budget).build();
        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "system"),
                Message.of(RoleType.USER, "hello")));

        lifecycle.beforeModel(request, context);
        var first = request.messages.getFirst();
        assertTrue(first.getTextContent().contains("Status: NORMAL"));

        clock.set(100_000_000_000L);
        lifecycle.beforeModel(request, context);
        assertSame(first, request.messages.getFirst());

        clock.set(500_000_000_000L);
        lifecycle.beforeModel(request, context);
        assertNotSame(first, request.messages.getFirst());
        assertTrue(request.messages.getFirst().getTextContent().contains("Status: CONSERVE"));
    }

    @Test
    void lifecycleReaddsBlockAfterSystemPromptReload() {
        var clock = new AtomicLong();
        var time = TimeBudget.ofSeconds(900, 135L, 0, Instant.EPOCH, clock::get);
        var budget = new RunBudget(time, null, null, new TokenEstimator(1.0), 0);
        var lifecycle = new RunBudgetLifecycle(budget);
        var context = ExecutionContext.builder().runBudget(budget).build();
        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "system"),
                Message.of(RoleType.USER, "hello")));

        lifecycle.beforeModel(request, context);
        var reloaded = new ArrayList<>(request.messages);
        reloaded.set(0, Message.of(RoleType.SYSTEM, "system"));
        request.messages = reloaded;

        lifecycle.beforeModel(request, context);
        assertTrue(request.messages.getFirst().getTextContent().contains("<run_budget>"));
    }

    @Test
    void promptTimeoutLeavesOnlyExitBuffer() {
        var clock = new AtomicLong(100_000_000_000L);
        var time = TimeBudget.ofSeconds(900, 135L, 0, Instant.EPOCH, clock::get);
        var budget = new RunBudget(time, null, null, new TokenEstimator(1.0), 0);

        assertEquals(780_000L, AgentSessionRunner.promptTimeoutMillis(budget, null).longValue());
        assertEquals(780_000L, AgentSessionRunner.promptTimeoutMillis(budget, 800).longValue());
        assertEquals(700_000L, AgentSessionRunner.promptTimeoutMillis(budget, 700).longValue());
    }

    @Test
    void replaceKeepsASingleBlock() {
        var clock = new AtomicLong();
        var budget = timeBudget(clock);
        var normal = RunBudgetSection.render(budget);
        clock.set(500_000_000_000L);
        var conserve = RunBudgetSection.render(budget);

        var replaced = RunBudgetSection.replace("system prompt\n\n" + normal, conserve);
        assertTrue(replaced.endsWith(conserve));
        assertTrue(RunBudgetSection.containsBudgetBlock(replaced));
        assertFalse(replaced.contains(normal));

        var stripped = RunBudgetSection.replace(replaced, null);
        assertFalse(RunBudgetSection.containsBudgetBlock(stripped));
        assertNull(RunBudgetSection.render(null));
    }
}
