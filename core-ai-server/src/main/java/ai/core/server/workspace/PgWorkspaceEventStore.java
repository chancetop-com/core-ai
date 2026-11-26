package ai.core.server.workspace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;

/**
 * @author stephen
 */
public class PgWorkspaceEventStore implements WorkspaceEventStore {
    private static final String TABLE = "app.workspace_events";

    private static WorkspaceEventRecord toEvent(PgWorkspaceEventRow row) {
        var event = new WorkspaceEventRecord();
        event.id = row.id;
        event.workspaceId = row.workspaceId;
        event.eventType = row.eventType;
        event.actor = row.actor;
        event.payloadJson = row.payloadJson;
        event.createdAt = row.createdAt;
        return event;
    }

    @Inject
    Database database;

    @Override
    public void append(WorkspaceEventRecord event) {
        database.execute("INSERT INTO " + TABLE + " (id, workspace_id, event_type, actor, payload_json, created_at) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?)",
            event.id, event.workspaceId, event.eventType, event.actor, event.payloadJson, event.createdAt);
    }

    @Override
    public List<WorkspaceEventRecord> list(String workspaceId, int limit) {
        return database.select("SELECT id, workspace_id, event_type, actor, payload_json::text AS payload_json, created_at "
                        + "FROM " + TABLE + " WHERE workspace_id = ? ORDER BY created_at DESC LIMIT ?",
                PgWorkspaceEventRow.class, workspaceId, limit).stream().map(PgWorkspaceEventStore::toEvent).toList();
    }

    @Override
    public int deleteByWorkspace(String workspaceId) {
        return database.execute("DELETE FROM " + TABLE + " WHERE workspace_id = ?", workspaceId);
    }
}
