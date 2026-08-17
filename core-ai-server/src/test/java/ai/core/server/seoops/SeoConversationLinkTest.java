package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.LinkConversationRequest;
import ai.core.server.domain.ChatSession;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class SeoConversationLinkTest {
    @Test
    void linksOwnedSessionUsingEligibleCopilot() {
        var service = service();
        var request = request("conversation-1", "link-1");

        var result = service.linkConversation("user-1", "task-1", request);

        assertEquals(1, result.conversationLinks.size());
        assertEquals("TASK_CHAT", result.conversationLinks.getFirst().relationship);
        assertEquals(2L, result.stateVersion);
    }

    @Test
    void replayIsIdempotentAndChangedContentConflicts() {
        var service = service();
        var first = service.linkConversation("user-1", "task-1", request("conversation-1", "link-1"));
        when(service.taskCollection.get("task-1")).thenReturn(java.util.Optional.of(first));

        assertEquals(first, service.linkConversation("user-1", "task-1", request("conversation-1", "link-1")));
        assertThrows(ConflictException.class,
            () -> service.linkConversation("user-1", "task-1", request("conversation-2", "link-1")));
    }

    @SuppressWarnings("unchecked")
    private SeoTaskCommandService service() {
        var task = task();
        var service = new SeoTaskCommandService();
        service.taskCollection = mock(MongoCollection.class);
        service.merchantService = mock(SeoMerchantService.class);
        service.conversationPolicy = mock(SeoConversationPolicy.class);
        service.copilotPolicy = mock(SeoCopilotPolicy.class);
        when(service.taskCollection.get("task-1")).thenReturn(java.util.Optional.of(task));
        when(service.taskCollection.update(any(), any())).thenReturn(1L);
        when(service.copilotPolicy.eligibleAgentId()).thenReturn(java.util.Optional.of("agent-safe"));
        when(service.conversationPolicy.requireOwnedChatSession(any(), any())).thenAnswer(invocation -> {
            var session = new ChatSession();
            session.id = invocation.getArgument(1);
            session.agentId = "agent-safe";
            return session;
        });
        return service;
    }

    private SeoTask task() {
        var task = new SeoTask();
        task.id = "task-1";
        task.merchantId = "merchant-1";
        task.taskRevision = 1L;
        task.stateVersion = 1L;
        task.status = SeoTaskStatus.DRAFT;
        task.currentRevision = new SeoTask.TaskRevision();
        task.currentRevision.executionSpecHash = "sha256:spec";
        task.conversationLinks = new ArrayList<>();
        task.events = new ArrayList<>();
        task.updatedAt = ZonedDateTime.now();
        return task;
    }

    private LinkConversationRequest request(String conversationId, String key) {
        var request = new LinkConversationRequest();
        request.conversationId = conversationId;
        request.expectedStateVersion = 1L;
        request.idempotencyKey = key;
        return request;
    }
}
