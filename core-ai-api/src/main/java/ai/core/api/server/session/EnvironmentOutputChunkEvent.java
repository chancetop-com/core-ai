package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author lim chen
 */
public class EnvironmentOutputChunkEvent implements AgentEvent {
    public static EnvironmentOutputChunkEvent of(String sessionId, String source, String callId, String chunk) {
        var event = new EnvironmentOutputChunkEvent();
        event.sessionId = sessionId;
        event.source = source;
        event.callId = callId;
        event.chunk = chunk;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "source")
    public String source;

    @NotNull
    @Property(name = "callId")
    public String callId;

    @NotNull
    @Property(name = "chunk")
    public String chunk;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
