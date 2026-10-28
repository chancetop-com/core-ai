package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class TurnCompleteEvent implements AgentEvent {
    public static TurnCompleteEvent of(String sessionId, String output) {
        return of(sessionId, output, null);
    }

    public static TurnCompleteEvent of(String sessionId, String output, String turnId) {
        var event = new TurnCompleteEvent();
        event.sessionId = sessionId;
        event.output = output;
        event.turnId = turnId;
        event.cancelled = Boolean.FALSE;
        return event;
    }

    public static TurnCompleteEvent cancelled(String sessionId) {
        return cancelled(sessionId, "");
    }

    /**
     * Cancelled turn that may still carry partial output (e.g. text produced before ESC),
     * so persistence listeners can record what was produced before the interruption.
     */
    public static TurnCompleteEvent cancelled(String sessionId, String output) {
        return cancelled(sessionId, output, null);
    }

    public static TurnCompleteEvent cancelled(String sessionId, String output, String turnId) {
        var event = new TurnCompleteEvent();
        event.sessionId = sessionId;
        event.output = output != null ? output : "";
        event.turnId = turnId;
        event.cancelled = Boolean.TRUE;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @Property(name = "output")
    public String output;

    /** Caller-supplied correlation id (e.g. an OCG mailbox message id); null for older clients. */
    @Property(name = "turn_id")
    public String turnId;

    @NotNull
    @Property(name = "cancelled")
    public Boolean cancelled;

    @NotNull
    @Property(name = "max_turns_reached")
    public Boolean maxTurnsReached = Boolean.FALSE;

    /** Turn wall time (RUNNING dispatch to turn end), in milliseconds. */
    @Property(name = "duration_ms")
    public Long durationMs;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    /** Turn delta of prompt-cache hits, when the provider reports them. */
    @Property(name = "cached_tokens")
    public Long cachedTokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @NotNull
    @Property(name = "budget_exhausted")
    public Boolean budgetExhausted = Boolean.FALSE;

    @Property(name = "budget_level")
    public String budgetLevel;

    @Property(name = "budget_binding")
    public String budgetBinding;

    @Property(name = "budget_tokens_spent")
    public Long budgetTokensSpent;

    @Property(name = "budget_cost_spent")
    public Double budgetCostSpent;

    @Property(name = "budget_cost_estimated")
    public Boolean budgetCostEstimated;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
