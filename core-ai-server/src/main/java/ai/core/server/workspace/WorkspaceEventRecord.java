package ai.core.server.workspace;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Audit event for workspace lifecycle changes. Payloads carry ids, states and durations only —
 * never file contents or secret material.
 *
 * @author stephen
 */
public class WorkspaceEventRecord {
    public static WorkspaceEventRecord of(String workspaceId, String eventType, String actor, String payloadJson) {
        var event = new WorkspaceEventRecord();
        event.id = UUID.randomUUID().toString();
        event.workspaceId = workspaceId;
        event.eventType = eventType;
        event.actor = actor;
        event.payloadJson = payloadJson;
        event.createdAt = ZonedDateTime.now();
        return event;
    }

    public String id;
    public String workspaceId;
    public String eventType;
    public String actor;
    public String payloadJson;
    public ZonedDateTime createdAt;

}
