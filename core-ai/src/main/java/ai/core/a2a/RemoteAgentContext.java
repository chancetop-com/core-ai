package ai.core.a2a;

import ai.core.api.a2a.TaskState;

import java.time.Instant;
import java.util.Map;

/**
 * Local snapshot of a remote A2A agent context.
 *
 * @author xander
 */
public class RemoteAgentContext {
    public String localSessionId;
    public String remoteAgentId;
    public String contextId;
    public String lastTaskId;
    public TaskState lastState;
    public Instant updatedAt;
    public Map<String, Object> metadata;

    public boolean hasContextId() {
        return contextId != null && !contextId.isBlank();
    }
}
