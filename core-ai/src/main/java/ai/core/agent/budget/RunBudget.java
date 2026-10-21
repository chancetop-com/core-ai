package ai.core.agent.budget;

import ai.core.agent.CancellationToken;
import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Usage;

import java.time.Instant;

/**
 * Run-level budget composed of independent time, token and cost dimensions.
 * The combined level is the most severe dimension; binding identifies the
 * dimension that currently constrains the run.
 *
 * @author stephen
 */
public final class RunBudget {
    private static final int MIN_OUTPUT_TOKENS = 256;
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    private static double ratio(long limit, long remaining) {
        return limit <= 0 ? 1.0 : 1.0 - (double) remaining / limit;
    }

    private static BudgetLevel levelOf(TimeBudget budget) {
        return budget == null ? BudgetLevel.NORMAL : budget.level();
    }

    private static BudgetLevel levelOf(TokenBudget budget) {
        return budget == null ? BudgetLevel.NORMAL : budget.level();
    }

    private static BudgetLevel levelOf(CostBudget budget) {
        return budget == null ? BudgetLevel.NORMAL : budget.level();
    }

    private final TimeBudget time;
    private final TokenBudget tokens;
    private final CostBudget cost;
    private final BudgetLedger ledger;
    private final TokenEstimator estimator;
    private final long toolResultMaxTokens;
    private final boolean subagentView;
    private volatile boolean hardLimitReached;
    private volatile boolean costEstimateMissing;

    public RunBudget(TimeBudget time, TokenBudget tokens, CostBudget cost, TokenEstimator estimator,
                     long toolResultMaxTokens) {
        this.time = time;
        this.tokens = tokens;
        this.cost = cost;
        this.ledger = new BudgetLedger();
        this.estimator = estimator;
        this.toolResultMaxTokens = Math.max(0, toolResultMaxTokens);
        this.subagentView = false;
    }

    private RunBudget(RunBudget source, boolean subagentView) {
        this.time = source.time;
        this.tokens = source.tokens;
        this.cost = source.cost;
        this.ledger = source.ledger;
        this.estimator = source.estimator;
        this.toolResultMaxTokens = source.toolResultMaxTokens;
        this.subagentView = subagentView;
    }

    public TimeBudget time() {
        return time;
    }

    public TokenBudget tokens() {
        return tokens;
    }

    public CostBudget cost() {
        return cost;
    }

    public long toolResultMaxTokens() {
        return toolResultMaxTokens;
    }

    public boolean needsModelAdmission() {
        return tokens != null || cost != null;
    }

    public RunBudget childScope() {
        return new RunBudget(this, true);
    }

    public BudgetLevel level() {
        return BudgetLevel.worst(BudgetLevel.worst(levelOf(time), levelOf(tokens)), levelOf(cost));
    }

    public BudgetBinding binding() {
        var best = BudgetBinding.TIME;
        double bestScore = Double.NEGATIVE_INFINITY;
        if (time != null) {
            double score = time.level().ordinal() * 10.0 + ratio(time.totalMillis(), time.remainingMillis());
            if (score > bestScore) {
                best = BudgetBinding.TIME;
                bestScore = score;
            }
        }
        if (tokens != null && tokens.limitTokens() > 0) {
            double score = tokens.level().ordinal() * 10.0 + ratio(tokens.limitTokens(), tokens.remainingTokens());
            if (score > bestScore) {
                best = BudgetBinding.TOKENS;
                bestScore = score;
            }
        }
        if (cost != null && cost.limitMicros() > 0) {
            double score = cost.level().ordinal() * 10.0 + ratio(cost.limitMicros(), cost.remainingMicros());
            if (score > bestScore) {
                best = BudgetBinding.COST;
            }
        }
        return best;
    }

    public BudgetCall admitModel(CompletionRequest request, String model) {
        long estimatedPrompt = estimator.estimatePrompt(request);
        var maxOutput = resolveMaxOutput(request, model, estimatedPrompt);
        if (maxOutput == null) {
            return BudgetCall.reject("BUDGET_EXHAUSTED: not enough output budget for the next model call");
        }
        long estimatedCost = 0;
        if (cost != null && cost.limitMicros() > 0) {
            var micros = estimateCostMicros(model, estimatedPrompt, maxOutput, 0);
            if (micros == null) {
                return BudgetCall.reject("COST_PRICING_UNAVAILABLE: no price for model " + model);
            }
            estimatedCost = micros;
        }
        var reservation = ledger.reserve(estimatedPrompt + maxOutput, estimatedCost, tokens, cost);
        if (!reservation.allowed()) {
            return BudgetCall.reject(reservation.reason());
        }
        if (tokens != null) {
            request.maxCompletionTokens = maxOutput;
        }
        return BudgetCall.accept(reservation, estimatedPrompt, maxOutput);
    }

    private Integer resolveMaxOutput(CompletionRequest request, String model, long estimatedPrompt) {
        long modelMax = DEFAULT_MAX_OUTPUT_TOKENS;
        var info = LLMModelContextRegistry.getInstance().getModelInfo(model);
        if (info != null) modelMax = info.maxOutputTokens();
        long max = modelMax;
        if (tokens != null) {
            long availableTokens = subagentView ? tokens.softRemainingTokens() : tokens.remainingTokens();
            long available = availableTokens - estimatedPrompt;
            long outputRemaining = tokens.remainingOutputTokens();
            if (outputRemaining != Long.MAX_VALUE) available = Math.min(available, outputRemaining);
            max = Math.min(max, available);
        }
        if (request.maxCompletionTokens != null) {
            max = Math.min(max, request.maxCompletionTokens);
        }
        if (max < MIN_OUTPUT_TOKENS) return null;
        return (int) Math.min(max, Integer.MAX_VALUE);
    }

    public void settleModel(BudgetCall call, CompletionRequest request, CompletionResponse response) {
        if (call == null || !call.markSettled()) return;
        var usage = response.usage != null ? response.usage : estimator.estimateUsage(request, response);
        long actualMicros = 0;
        if (cost != null && cost.limitMicros() > 0) {
            var micros = estimateCostMicros(request.model, usage);
            actualMicros = micros == null ? 0 : micros;
            if (micros == null) costEstimateMissing = true;
        }
        ledger.settle(call.reservation(), usage, actualMicros, tokens, cost);
        if (response.usage != null && call.estimatedPromptTokens() > 0) {
            estimator.calibrate(call.estimatedPromptTokens(), response.usage.getPromptTokens());
        }
        if (isHardLimitReached()) hardLimitReached = true;
    }

    public void release(BudgetCall call) {
        if (call != null) ledger.release(call.reservation());
    }

    public void recordExternalUsage(String source, String model, Usage usage) {
        if (usage == null) return;
        if (tokens != null) tokens.record(usage);
        if (cost != null && cost.limitMicros() > 0) {
            var micros = estimateCostMicros(model, usage);
            if (micros == null) {
                costEstimateMissing = true;
            } else {
                cost.recordMicros(micros);
            }
        }
    }

    public ToolBudgetDecision admitToolCall(BudgetProfile profile, long requestedTimeoutMs) {
        var effectiveProfile = profile == null ? BudgetProfile.normal(requestedTimeoutMs) : profile;
        long effectiveTimeout = requestedTimeoutMs;
        if (time != null) {
            long remaining = time.remainingMillis();
            if (remaining <= 0) {
                return ToolBudgetDecision.reject("TIME_BUDGET_EXHAUSTED: no time left to start the tool");
            }
            long available = remaining - time.reserveMillis();
            switch (effectiveProfile.budgetClass()) {
                case CHEAP -> effectiveTimeout = Math.max(1, Math.min(requestedTimeoutMs,
                        Math.min(remaining, 10_000)));
                case NORMAL -> {
                    if (available < 5_000) {
                        return ToolBudgetDecision.reject("TIME_BUDGET_EXHAUSTED: fewer than 5s left before the reserve");
                    }
                    effectiveTimeout = Math.max(1, Math.min(requestedTimeoutMs, available));
                }
                case EXPENSIVE -> {
                    if (available < 10_000) {
                        return ToolBudgetDecision.reject("TIME_BUDGET_EXHAUSTED: fewer than 10s left before the reserve");
                    }
                    effectiveTimeout = Math.max(1, Math.min(requestedTimeoutMs, available));
                }
                default -> throw new IllegalStateException("unknown budget class: " + effectiveProfile.budgetClass());
            }
        }
        if (tokens != null && tokens.limitTokens() > 0 && effectiveProfile.estimatedOutputTokens() > 0) {
            long available = (subagentView ? tokens.softRemainingTokens() : tokens.remainingTokens())
                    - effectiveProfile.estimatedOutputTokens();
            if (available < 1_024) {
                return ToolBudgetDecision.reject("TOKEN_BUDGET_EXHAUSTED: the tool result would exceed the remaining token budget");
            }
        }
        if (cost != null && cost.limitMicros() > 0 && effectiveProfile.estimatedCostUsd() > 0) {
            long available = subagentView ? cost.softRemainingMicros() : cost.remainingMicros();
            if (available < CostBudget.toMicros(effectiveProfile.estimatedCostUsd())) {
                return ToolBudgetDecision.reject("COST_BUDGET_EXHAUSTED: the tool would exceed the remaining cost budget");
            }
        }
        return ToolBudgetDecision.allow(effectiveTimeout);
    }

    public void settleToolCost(double usd, String source) {
        if (cost == null || usd <= 0) return;
        long micros = CostBudget.toMicros(usd);
        if ("media".equals(source)) {
            cost.recordMediaMicros(micros);
        } else {
            cost.recordMicros(micros);
        }
        if (isHardLimitReached()) hardLimitReached = true;
    }

    public Runnable arm(CancellationToken token) {
        return time == null ? () -> {
        } : time.arm(token);
    }

    public boolean isHardLimitReached() {
        return hardLimitReached
                || tokens != null && tokens.limitTokens() > 0 && tokens.spentTokens() >= tokens.limitTokens()
                || cost != null && cost.limitMicros() > 0 && cost.spentMicros() >= cost.limitMicros();
    }

    public boolean costEstimateMissing() {
        return costEstimateMissing;
    }

    private Long estimateCostMicros(String model, Usage usage) {
        var cached = usage.getPromptTokensDetails() != null
                ? usage.getPromptTokensDetails().cachedTokens : 0;
        return estimateCostMicros(model, usage.getPromptTokens(), usage.getCompletionTokens(), cached);
    }

    private Long estimateCostMicros(String model, long promptTokens, long completionTokens, long cachedTokens) {
        var usd = LLMModelContextRegistry.getInstance().estimateCostUsd(
                model, promptTokens, completionTokens, cachedTokens, Instant.now());
        return usd == null ? null : Math.round(usd * CostBudget.MICROS_PER_USD);
    }
}
