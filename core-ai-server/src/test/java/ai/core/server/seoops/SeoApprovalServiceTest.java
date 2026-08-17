package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.ApprovalDecisionRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.ApprovalPreviewRequest;
import ai.core.server.run.AgentRunner;
import ai.core.server.seoops.domain.SeoApprovalAction;
import ai.core.server.seoops.domain.SeoEvidenceState;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeoApprovalServiceTest {
    private final MongoCollection<SeoTask> tasks = mock();
    private final SeoMerchantService merchants = mock();
    private final SeoTaskCommandService service = new SeoTaskCommandService();

    @BeforeEach
    void setUp() {
        service.taskCollection = tasks;
        service.merchantService = merchants;
    }

    @Test
    void previewReturnsBlockersInsteadOfThrowingForUnreadyTask() {
        var task = task(SeoTaskStatus.NEEDS_INPUT, SeoEvidenceState.PARTIAL);
        when(tasks.get("task-1")).thenReturn(Optional.of(task));
        var request = previewRequest(task);

        var preview = service.preview("user-1", "task-1", request);

        assertFalse(preview.reviewable());
        assertEquals(List.of("task status is NEEDS_INPUT", "evidence state is PARTIAL"), preview.blockers());
    }

    @Test
    void previewPinsRevisionVersionAndExecutionHash() {
        var task = task(SeoTaskStatus.READY_FOR_APPROVAL, SeoEvidenceState.VERIFIED);
        when(tasks.get("task-1")).thenReturn(Optional.of(task));

        var preview = service.preview("user-1", "task-1", previewRequest(task));

        assertTrue(preview.reviewable());
        assertEquals(3L, preview.stateVersion());
        assertEquals("sha256:abc", preview.executionSpecHash());
    }

    @Test
    void approvalAtomicallyAdvancesStateAndAppendsAuditRecords() {
        var task = task(SeoTaskStatus.READY_FOR_APPROVAL, SeoEvidenceState.VERIFIED);
        when(tasks.get("task-1")).thenReturn(Optional.of(task));
        when(tasks.update(any(), any())).thenReturn(1L);

        var updated = service.decide("user-1", "task-1", decisionRequest("APPROVE", null));

        assertEquals(SeoTaskStatus.APPROVED, updated.status);
        assertEquals(4L, updated.stateVersion);
        assertEquals(1, updated.approvalDecisions.size());
        assertEquals(SeoApprovalAction.APPROVE, updated.approvalDecisions.getFirst().decision);
        assertEquals("APPROVAL_DECIDED", updated.events.getLast().type);
        verify(tasks).update(any(), any());
    }

    @Test
    void rejectAndRevokeRequireReason() {
        var task = task(SeoTaskStatus.READY_FOR_APPROVAL, SeoEvidenceState.VERIFIED);
        when(tasks.get("task-1")).thenReturn(Optional.of(task));

        assertThrows(BadRequestException.class,
            () -> service.decide("user-1", "task-1", decisionRequest("REJECT", " ")));
        task.status = SeoTaskStatus.APPROVED;
        assertThrows(BadRequestException.class,
            () -> service.decide("user-1", "task-1", decisionRequest("REVOKE", null)));
        verify(tasks, never()).update(any(), any());
    }

    @Test
    void staleHashOrStateCannotAuthorizeExecutionSpec() {
        var task = task(SeoTaskStatus.READY_FOR_APPROVAL, SeoEvidenceState.VERIFIED);
        when(tasks.get("task-1")).thenReturn(Optional.of(task));
        var request = decisionRequest("APPROVE", null);
        request.executionSpecHash = "sha256:stale";

        assertThrows(ConflictException.class,
            () -> service.decide("user-1", "task-1", request));
        verify(tasks, never()).update(any(), any());
    }

    @Test
    void commandServiceHasNoExecutionDependency() {
        assertTrue(java.util.Arrays.stream(SeoTaskCommandService.class.getDeclaredFields())
            .noneMatch(field -> AgentRunner.class.isAssignableFrom(field.getType())));
    }

    private SeoTask task(SeoTaskStatus status, SeoEvidenceState evidenceState) {
        var task = new SeoTask();
        task.id = "task-1";
        task.merchantId = "merchant-1";
        task.taskRevision = 2L;
        task.stateVersion = 3L;
        task.status = status;
        task.evidenceState = evidenceState;
        task.revisions = new ArrayList<>();
        task.evidenceRefs = new ArrayList<>();
        task.approvalDecisions = new ArrayList<>();
        task.conversationLinks = new ArrayList<>();
        task.agentRunLinks = new ArrayList<>();
        task.events = new ArrayList<>();
        var revision = new SeoTask.TaskRevision();
        revision.revision = 2L;
        revision.executionSpecHash = "sha256:abc";
        revision.requiredEvidenceTypes = List.of("SOURCE");
        task.currentRevision = revision;
        return task;
    }

    private ApprovalPreviewRequest previewRequest(SeoTask task) {
        var request = new ApprovalPreviewRequest();
        request.taskRevision = task.taskRevision;
        request.expectedStateVersion = task.stateVersion;
        return request;
    }

    private ApprovalDecisionRequest decisionRequest(String decision, String reason) {
        var request = new ApprovalDecisionRequest();
        request.decision = decision;
        request.reason = reason;
        request.taskRevision = 2L;
        request.executionSpecHash = "sha256:abc";
        request.expectedStateVersion = 3L;
        request.idempotencyKey = "decision-1";
        return request;
    }
}
