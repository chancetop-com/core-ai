package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseLlmFirstTokenEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "ttft_ms")
    public Long ttftMs;
}
