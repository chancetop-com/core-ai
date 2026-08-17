package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.AppendEvidenceRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateRevisionRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateTaskRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.TaskDefinitionRequest;
import ai.core.server.domain.ChatSession;
import ai.core.server.seoops.domain.SeoEvidenceState;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoLocationReadiness;
import ai.core.server.seoops.domain.SeoMerchant;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeoTaskCommandServiceTest {
    private final MongoCollection<SeoTask> tasks = mock();
    private final SeoMerchantService merchants = mock();
    private final SeoConversationPolicy conversations = mock();
    private final SeoTaskCommandService service = new SeoTaskCommandService();
    private final SeoMerchant merchant = merchant();
    private final SeoLocation location = location();

    @BeforeEach
    void setUp() {
        service.taskCollection = tasks;
        service.merchantService = merchants;
        service.hasher = new SeoExecutionSpecHasher();
        service.taskPolicy = new SeoTaskPolicy();
        service.conversationPolicy = conversations;
        when(merchants.requireVisibleMerchant("user-1", "merchant-1")).thenReturn(merchant);
        when(merchants.requireVisibleLocation("user-1", "merchant-1", "location-1")).thenReturn(location);
        when(tasks.find(any(org.bson.conversions.Bson.class))).thenReturn(List.of());
    }

    @Test
    void createsRevisionedTaskAndDerivesReadinessWithoutDispatch() {
        var request = createTaskRequest();

        var created = service.createTask("user-1", request);

        var inserted = ArgumentCaptor.forClass(SeoTask.class);
        verify(tasks).insert(inserted.capture());
        assertEquals(1L, created.taskRevision);
        assertEquals(1L, created.stateVersion);
        assertEquals(0, created.priorityRank);
        assertEquals(SeoTaskStatus.NEEDS_INPUT, created.status);
        assertEquals(SeoEvidenceState.NONE, created.evidenceState);
        assertEquals("{\"asset\":\"menu\",\"operation\":\"publish\"}", created.currentRevision.executionSpec);
        assertTrue(created.currentRevision.executionSpecHash.matches("sha256:[0-9a-f]{64}"));
        assertEquals("TASK_CREATED", created.events.getFirst().type);
        assertEquals(List.of(), created.agentRunLinks);
    }

    @Test
    void supportsMerchantLevelTaskWithoutLocation() {
        var request = createTaskRequest();
        request.locationId = null;
        request.definition.requiredEvidenceTypes = List.of();

        var created = service.createTask("user-1", request);

        assertNull(created.locationId);
        assertEquals(SeoTaskStatus.READY_FOR_APPROVAL, created.status);
    }

    @Test
    void rejectsUnknownPriorityAndOwnerOutsideMerchantOperators() {
        var invalidPriority = createTaskRequest();
        invalidPriority.definition.priority = "P0";
        assertThrows(BadRequestException.class, () -> service.createTask("user-1", invalidPriority));

        var invalidOwner = createTaskRequest();
        invalidOwner.definition.ownerId = "outside-user";
        assertThrows(BadRequestException.class, () -> service.createTask("user-1", invalidOwner));
        verify(tasks, never()).insert(any());
    }

    @Test
    void linksOwnedOriginatingConversationWithoutCopyingTranscript() {
        var request = createTaskRequest();
        request.definition.conversationId = "session-1";
        var session = new ChatSession();
        session.id = "session-1";
        when(conversations.requireOwnedChatSession("user-1", "session-1")).thenReturn(session);

        var created = service.createTask("user-1", request);

        assertEquals(1, created.conversationLinks.size());
        assertEquals("ORIGINATING_DRAFT", created.conversationLinks.getFirst().relationship);
        assertEquals("session-1", created.conversationLinks.getFirst().conversationId);
    }

    @Test
    void staleRevisionFailsBeforeMutation() {
        var existing = existingTask();
        when(tasks.get("task-1")).thenReturn(java.util.Optional.of(existing));
        var request = new CreateRevisionRequest();
        request.definition = definition();
        request.expectedStateVersion = 9L;
        request.idempotencyKey = "revision-2";

        assertThrows(ConflictException.class,
            () -> service.createRevision("user-1", "task-1", request));
        verify(tasks, never()).update(any(), any());
    }

    @Test
    void verifiedEvidenceAdvancesCurrentRevisionToApprovalReadiness() {
        var existing = existingTask();
        when(tasks.get("task-1")).thenReturn(java.util.Optional.of(existing));
        when(tasks.update(any(), any())).thenReturn(1L);
        var request = evidenceRequest();

        var updated = service.appendEvidence("user-1", "task-1", request);

        assertEquals(2L, updated.stateVersion);
        assertEquals(SeoEvidenceState.VERIFIED, updated.evidenceState);
        assertEquals(SeoTaskStatus.READY_FOR_APPROVAL, updated.status);
        assertEquals(1, updated.evidenceRefs.size());
        assertEquals("EVIDENCE_APPENDED", updated.events.getLast().type);
        verify(tasks).update(any(), any());
    }

    @Test
    void byteBackedEvidenceRequiresExactlyOneSourceAndSha256() {
        var existing = existingTask();
        when(tasks.get("task-1")).thenReturn(java.util.Optional.of(existing));
        var request = evidenceRequest();
        request.fileId = "file-1";
        request.sourceRef = "https://example.com/report";
        assertThrows(BadRequestException.class,
            () -> service.appendEvidence("user-1", "task-1", request));

        request.sourceRef = null;
        request.sha256 = null;
        assertThrows(BadRequestException.class,
            () -> service.appendEvidence("user-1", "task-1", request));
        verify(tasks, never()).update(any(), any());
    }

    private CreateTaskRequest createTaskRequest() {
        var request = new CreateTaskRequest();
        request.merchantId = "merchant-1";
        request.locationId = "location-1";
        request.definition = definition();
        request.idempotencyKey = "task-create-1";
        return request;
    }

    private TaskDefinitionRequest definition() {
        var definition = new TaskDefinitionRequest();
        definition.title = "Publish verified menu page";
        definition.taskType = "WEBSITE_PUBLISH";
        definition.source = "INTERNAL_PLAN";
        definition.priority = "URGENT";
        definition.impact = "HIGH";
        definition.ownerId = "user-1";
        definition.dueAt = "2026-08-18T10:00:00-04:00";
        definition.executionSpec = "{\"operation\":\"publish\",\"asset\":\"menu\"}";
        definition.requiredEvidenceTypes = List.of("SOURCE");
        return definition;
    }

    private AppendEvidenceRequest evidenceRequest() {
        var request = new AppendEvidenceRequest();
        request.type = "SOURCE_DOCUMENT";
        request.sourceRef = "merchant-confirmed-menu-v1";
        request.capturedAt = "2026-08-17T12:00:00Z";
        request.verificationStatus = "VERIFIED";
        request.requirementKey = "SOURCE";
        request.expectedStateVersion = 1L;
        request.idempotencyKey = "evidence-1";
        return request;
    }

    private SeoTask existingTask() {
        var task = service.createTask("user-1", createTaskRequest());
        task.id = "task-1";
        task.creationIdempotencyKey = "original-create";
        return task;
    }

    private SeoMerchant merchant() {
        var value = new SeoMerchant();
        value.id = "merchant-1";
        value.operatorUserIds = List.of("user-1", "user-2");
        return value;
    }

    private SeoLocation location() {
        var value = new SeoLocation();
        value.id = "location-1";
        value.merchantId = "merchant-1";
        value.readinessStatus = SeoLocationReadiness.READY;
        return value;
    }
}
