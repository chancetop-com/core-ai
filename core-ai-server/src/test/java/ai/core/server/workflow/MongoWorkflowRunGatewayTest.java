package ai.core.server.workflow;

import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MongoWorkflowRunGatewayTest {
    @Test
    void nestedPinnedRunRejectsPreviouslyPublishedUnsafeVersionBeforeInsert() {
        var version = new WorkflowPublishedVersion();
        version.id = "child-workflow:v1";
        version.workflowId = "child-workflow";
        @SuppressWarnings("unchecked")
        MongoCollection<WorkflowPublishedVersion> versions = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        MongoCollection<WorkflowRun> runs = mock(MongoCollection.class);
        when(versions.get(version.id)).thenReturn(Optional.of(version));
        var validator = mock(WorkflowPrivateAgentSafetyValidator.class);
        var blocked = new WorkflowValidationException(List.of("node n1 has an unsafe legacy snapshot"));
        doThrow(blocked).when(validator).requireSafe(version);
        var gateway = new MongoWorkflowRunGateway();
        gateway.versionCollection = versions;
        gateway.runCollection = runs;
        gateway.privateAgentSafetyValidator = validator;
        var parent = new WorkflowRun();
        parent.id = "parent-run";
        parent.userId = "viewer";
        var node = new WorkflowNode("child", "WORKFLOW", List.of(), Map.of(
            "source_workflow_id", version.workflowId,
            "version_id", version.id));

        var actual = assertThrows(WorkflowValidationException.class,
            () -> gateway.submitChildRun(parent, node, "{}", 1));

        assertSame(blocked, actual);
        verifyNoInteractions(runs);
    }
}
