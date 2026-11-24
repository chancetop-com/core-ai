package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceEventRow {
    @Column(name = "id")
    public String id;

    @Column(name = "workspace_id")
    public String workspaceId;

    @Column(name = "event_type")
    public String eventType;

    @Column(name = "actor")
    public String actor;

    @Column(name = "payload_json")
    public String payloadJson;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;
}
