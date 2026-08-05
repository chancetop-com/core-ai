package ai.core.server.web;

import ai.core.api.server.workflow.CreateRunRequest;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowVisibility;
import core.framework.json.JSON;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Xander
 */
class WorkflowViewMapperTest {
    @Test
    void versionViewDoesNotExposeAgentSnapshotsOrProvenance() {
        var version = new WorkflowPublishedVersion();
        version.id = "wf:v1";
        version.agentSnapshots = Map.of("n1", "private prompt value");
        version.agentSnapshotSources = Map.of("n1", "private source metadata");

        String json = JSON.toJSON(WorkflowViewMapper.toVersionView(version, null, false));

        assertFalse(json.contains("agent_snapshots"));
        assertFalse(json.contains("agent_snapshot_sources"));
        assertFalse(json.contains("private prompt value"));
        assertFalse(json.contains("private source metadata"));
    }

    @Test
    void runVisibilityIsNullWhenCallerDoesNotChoose() {
        // null = no choice; the run service then inherits the workflow's visibility
        assertNull(WorkflowViewMapper.runVisibility(null));
        assertNull(WorkflowViewMapper.runVisibility(new CreateRunRequest()));
        var blank = new CreateRunRequest();
        blank.visibility = "  ";
        assertNull(WorkflowViewMapper.runVisibility(blank));
    }

    @Test
    void runVisibilityKeepsExplicitChoice() {
        var request = new CreateRunRequest();
        request.visibility = "private";
        assertEquals(WorkflowVisibility.PRIVATE, WorkflowViewMapper.runVisibility(request));
        request.visibility = "PUBLIC";
        assertEquals(WorkflowVisibility.PUBLIC, WorkflowViewMapper.runVisibility(request));
    }

    @Test
    void runVisibilityRejectsUnknownValue() {
        var request = new CreateRunRequest();
        request.visibility = "TEAM";
        assertThrows(BadRequestException.class, () -> WorkflowViewMapper.runVisibility(request));
    }
}
