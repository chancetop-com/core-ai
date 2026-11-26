package ai.core.server.workspace;

import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Best-effort lifecycle audit: a failed event insert never fails the operation it describes.
 *
 * @author stephen
 */
class WorkspaceAudit {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceAudit.class);

    private final WorkspaceEventStore events;

    WorkspaceAudit(WorkspaceEventStore events) {
        this.events = events;
    }

    void append(String workspaceId, String type, String actor, Map<String, Object> payload) {
        try {
            events.append(WorkspaceEventRecord.of(workspaceId, type, actor, JSON.toJSON(payload)));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to append workspace event, workspace={}, type={}", workspaceId, type, e);
        }
    }

    void delete(String workspaceId) {
        try {
            events.deleteByWorkspace(workspaceId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to delete workspace events, workspace={}", workspaceId, e);
        }
    }
}
