package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * First content or reasoning token of one LLM call, measured from the provider request to that
 * token (TTFT). It is the same probe measurement that feeds the LLM span's
 * gen_ai.response.time_to_first_token_ms attribute, so the live view and the server latency
 * metrics report the same value.
 *
 * @author stephen
 */
public class LlmFirstTokenEvent implements AgentEvent {
    public static LlmFirstTokenEvent of(String sessionId, long ttftMs) {
        var event = new LlmFirstTokenEvent();
        event.sessionId = sessionId;
        event.ttftMs = ttftMs;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "ttft_ms")
    public Long ttftMs;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
