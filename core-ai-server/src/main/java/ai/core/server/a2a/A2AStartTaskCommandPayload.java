package ai.core.server.a2a;

import ai.core.api.a2a.SendMessageRequest;
import core.framework.api.json.Property;

/**
 * RPC payload for creating an A2A task on the Pod that owns an existing session.
 *
 * @author xander
 */
public class A2AStartTaskCommandPayload {
    @Property(name = "taskId")
    public String taskId;

    @Property(name = "agentId")
    public String agentId;

    @Property(name = "request")
    public SendMessageRequest request;

    @Property(name = "synchronous")
    public Boolean synchronous;
}
