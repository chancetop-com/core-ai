package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author lim chen
 */
public class SseEnvironmentOutputChunkEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "source")
    public String source;

    @NotNull
    @Property(name = "call_id")
    public String callId;

    @NotNull
    @Property(name = "chunk")
    public String chunk;
}
