package ai.core.server.workflow;

import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowRunServiceSafetyTest {
    @Test
    void publicRunRejectsPreviouslyPublishedUnsafeVersionBeforeInsert() {
        var definition = new WorkflowDefinition();
        definition.id = "workflow-1";
        definition.userId = "publisher";
        definition.visibility = WorkflowVisibility.PUBLIC;
        definition.publishedVersionId = "workflow-1:v1";
        var version = new WorkflowPublishedVersion();
        version.id = definition.publishedVersionId;
        version.workflowId = definition.id;
        @SuppressWarnings("unchecked")
        MongoCollection<WorkflowDefinition> definitions = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        MongoCollection<WorkflowPublishedVersion> versions = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        MongoCollection<WorkflowRun> runs = mock(MongoCollection.class);
        when(definitions.get(definition.id)).thenReturn(Optional.of(definition));
        when(versions.get(version.id)).thenReturn(Optional.of(version));
        var validator = mock(WorkflowPrivateAgentSafetyValidator.class);
        var blocked = new WorkflowValidationException(List.of("node n1 has an unsafe legacy snapshot"));
        doThrow(blocked).when(validator).requireSafe(version);
        var service = new WorkflowRunService();
        service.definitionCollection = definitions;
        service.versionCollection = versions;
        service.runCollection = runs;
        service.privateAgentSafetyValidator = validator;

        var actual = assertThrows(WorkflowValidationException.class,
            () -> service.createRun(definition.id, "{}", TriggerType.API, "viewer"));

        assertSame(blocked, actual);
        verifyNoInteractions(runs);
    }
}
