package ai.core.agent.budget;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class BudgetTest {
    @Test
    void timeBudgetLevelsAndReserve() {
        var clock = new AtomicLong();
        var budget = TimeBudget.ofSeconds(900, 135L, 0, Instant.EPOCH, clock::get);

        assertEquals(900_000, budget.remainingMillis());
        assertEquals(BudgetLevel.NORMAL, budget.level());

        clock.set(450_000_000_000L);
        assertEquals(450_000, budget.remainingMillis());
        // remaining is exactly half: the "> half" check has already dropped to CONSERVE
        assertEquals(BudgetLevel.CONSERVE, budget.level());

        clock.set(765_000_000_000L);
        assertEquals(135_000, budget.remainingMillis());
        assertEquals(BudgetLevel.FINAL, budget.level());

        clock.set(900_000_000_000L);
        assertTrue(budget.isExpired());
        assertEquals(0, budget.clampToolTimeout(10_000));
    }

    @Test
    void tokenBudgetCountsPromptCompletionAndCachedSubset() {
        var budget = TokenBudget.of(10_000, 4_000, 2_000L, 1_000L, true);
        budget.record(new Usage(6_000, 1_000, 7_000));
        assertEquals(7_000, budget.spentTokens());
        assertEquals(3_000, budget.remainingTokens());
        assertEquals(3_000, budget.remainingOutputTokens());
        assertEquals(BudgetLevel.CONSERVE, budget.level());

        var billable = TokenBudget.of(10_000, 0, 2_000L, null, false);
        var usage = new Usage(6_000, 1_000, 7_000);
        usage.setPromptTokensDetails(new Usage.PromptTokensDetails());
        usage.getPromptTokensDetails().cachedTokens = 2_000;
        billable.record(usage);
        assertEquals(5_000, billable.spentTokens());
        assertEquals(2_000, billable.cachedTokens());
    }

    @Test
    void costBudgetUsesMicrosAndReserve() {
        var budget = CostBudget.ofUsd(1.00, 0.10);
        assertEquals(1_000_000, budget.limitMicros());
        assertEquals(100_000, budget.reserveMicros());
        budget.recordMicros(500_000);
        assertEquals(500_000, budget.remainingMicros());
        assertEquals(BudgetLevel.CONSERVE, budget.level());
        budget.recordMediaMicros(250_000);
        assertEquals(250_000, budget.mediaSpentMicros());
    }

    @Test
    void ledgerRejectsConcurrentOversellAndSettles() {
        var tokens = TokenBudget.of(10_000, 0, 2_000L, null, true);
        var ledger = new BudgetLedger();
        var first = ledger.reserve(6_000, 0, tokens, null);
        assertTrue(first.allowed());
        var second = ledger.reserve(5_000, 0, tokens, null);
        assertFalse(second.allowed());
        assertNotNull(second.reason());

        ledger.settle(first, new Usage(5_000, 500, 5_500), 0, tokens, null);
        assertEquals(5_500, tokens.spentTokens());
        assertEquals(0, ledger.reservedTokens());
    }

    @Test
    void runBudgetAdmitsAndSettlesModelCall() {
        var tokens = TokenBudget.of(20_000, 4_000, 2_000L, 1_000L, true);
        var budget = new RunBudget(null, tokens, null, new TokenEstimator(1.0), 8_000);
        assertTrue(budget.needsModelAdmission());

        var request = new CompletionRequest();
        request.model = "gpt-4o";
        request.messages = List.of(Message.of(RoleType.SYSTEM, "system"), Message.of(RoleType.USER, "hello"));
        var call = budget.admitModel(request, request.model);
        assertTrue(call.allowed());
        assertNotNull(request.maxCompletionTokens);
        assertTrue(request.maxCompletionTokens > 0);

        budget.settleModel(call, request, CompletionResponse.of(List.of(), new Usage(100, 50, 150)));
        assertEquals(150, tokens.spentTokens());
        assertTrue(call.settled());
        assertFalse(budget.isHardLimitReached());
    }

    @Test
    void runBudgetRejectsExpensiveToolNearDeadlineAndAllowsCheap() {
        var clock = new AtomicLong();
        var time = TimeBudget.ofSeconds(20, 10L, 0, Instant.EPOCH, clock::get);
        var budget = new RunBudget(time, null, null, new TokenEstimator(1.0), 8_000);
        // 20s total, 10s reserve -> 10s available, still enough for a normal tool
        assertTrue(budget.admitToolCall(BudgetProfile.normal(120_000), 120_000).allowed());
        clock.set(15_000_000_000L);
        assertFalse(budget.admitToolCall(BudgetProfile.expensive(120_000), 120_000).allowed());
        assertTrue(budget.admitToolCall(BudgetProfile.cheap(120_000), 120_000).allowed());
    }

    @Test
    void tokenOnlyBudgetDoesNotTouchCompletionLimit() {
        var tokens = TokenBudget.of(10_000, 0, 2_000L, null, false);
        var budget = new RunBudget(null, tokens, CostBudget.ofUsd(1.00, 0.10), new TokenEstimator(1.0), 8_000);
        var request = new CompletionRequest();
        request.model = "gpt-4o";
        request.messages = List.of(Message.of(RoleType.SYSTEM, "system"), Message.of(RoleType.USER, "hello"));
        var call = budget.admitModel(request, request.model);
        assertTrue(call.allowed());
        assertNotNull(request.maxCompletionTokens);
    }

    @Test
    void finalBudgetAllowsCheapFileTools() {
        var clock = new AtomicLong();
        var time = TimeBudget.ofSeconds(100, 30L, 0, Instant.EPOCH, clock::get);
        var budget = new RunBudget(time, null, null, new TokenEstimator(1.0), 8_000);
        clock.set(80_000_000_000L);
        assertEquals(BudgetLevel.FINAL, budget.level());
        assertFalse(budget.admitToolCall(BudgetProfile.normal(1_000), 1_000).allowed());
        assertTrue(budget.admitToolCall(BudgetProfile.cheap(1_000), 1_000).allowed());
    }

    @Test
    void briefStatusShowsLiveBudget() {
        var clock = new AtomicLong();
        var budget = new RunBudget(TimeBudget.ofSeconds(900, 135L, 0, Instant.EPOCH, clock::get),
                null, null, new TokenEstimator(1.0), 8_000);
        clock.set(500_000_000_000L);
        var status = budget.briefStatus();
        assertTrue(status.contains("level CONSERVE"));
        assertTrue(status.contains("400s left"));
        assertTrue(status.contains("135s reserve"));
    }

    @Test
    void timeOnlyBudgetDoesNotNeedModelAdmission() {
        var clock = new AtomicLong();
        var budget = new RunBudget(TimeBudget.ofSeconds(60, 30L, 0, Instant.EPOCH, clock::get),
                null, null, new TokenEstimator(1.0), 8_000);
        assertFalse(budget.needsModelAdmission());
        assertNull(budget.tokens());
        assertNotNull(budget.time());
    }
}
