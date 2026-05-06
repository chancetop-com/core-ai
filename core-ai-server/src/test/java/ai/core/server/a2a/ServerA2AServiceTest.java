package ai.core.server.a2a;

import ai.core.api.a2a.Part;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.TaskState;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.session.AgentSessionManager;
import ai.core.session.InProcessAgentSession;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerA2AServiceTest {
    @Test
    void agentCardUsesPublishedServerToolsAsSkills() {
        var service = new ServerA2AService();
        service.agentDefinitionService = new FakeAgentDefinitionService(definition());

        var card = service.agentCard("agent-1");

        assertEquals("reviewer", card.name);
        assertEquals("review code", card.description);
        assertEquals("agent-1", card.supportedInterfaces.getFirst().tenant);
        assertEquals("/api/a2a", card.supportedInterfaces.getFirst().url);
        assertTrue(card.capabilities.streaming);
        assertEquals(List.of("text/plain", "application/json"), card.defaultInputModes);
        assertEquals("reviewer", card.skills.getFirst().name);
        assertTrue(card.skills.stream().anyMatch(skill -> "jira-search".equals(skill.id)));
        assertTrue(card.skills.stream().anyMatch(skill -> "github-mcp".equals(skill.id)));
    }

    @Test
    void resolverUsesTenantForUnifiedA2AEndpoint() {
        var request = mock(Request.class);
        when(request.queryParams()).thenReturn(Map.of());
        var messageRequest = request("hello");
        messageRequest.tenant = "agent-1";

        assertEquals("agent-1", A2AAgentIdResolver.resolve(request, messageRequest));
    }

    @Test
    void streamingResumeReusesExistingTaskAndRebindsStream() {
        var service = new ServerA2AService();
        service.agentDefinitionService = new FakeAgentDefinitionService(definition());
        var session = mockSession(service);

        var firstEvents = new ArrayList<StreamResponse>();
        var firstClosed = new AtomicBoolean(false);
        var state = service.stream("agent-1", request("hello"), "user-1", firstEvents::add, () -> firstClosed.set(true));
        fire(session.listeners, listener -> listener.onToolApprovalRequest(
                ToolApprovalRequestEvent.of("context-1", "call-1", "shell", "{}", null)));

        assertEquals(TaskState.INPUT_REQUIRED, state.getState());
        assertTrue(firstClosed.get());

        var secondEvents = new ArrayList<StreamResponse>();
        var resumeRequest = request("approve");
        resumeRequest.message.taskId = state.taskId;
        resumeRequest.message.parts = List.of(Part.data(Map.of("decision", "approve")));

        var resumed = service.stream("agent-1", resumeRequest, "user-1", secondEvents::add, () -> {
        });

        assertSame(state, resumed);
        verify(session.session).approveToolCall("call-1", ApprovalDecision.APPROVE);

        fire(session.listeners, listener -> listener.onTextChunk(TextChunkEvent.of("context-1", "done")));
        fire(session.listeners, listener -> listener.onTurnComplete(TurnCompleteEvent.of("context-1", "done")));

        assertTrue(secondEvents.stream().anyMatch(event -> event.artifactUpdate != null));
        assertEquals(TaskState.COMPLETED, state.getState());
    }

    private SendMessageRequest request(String text) {
        var request = new SendMessageRequest();
        request.message = ai.core.api.a2a.Message.user(text);
        return request;
    }

    private MockSession mockSession(ServerA2AService service) {
        var manager = mock(AgentSessionManager.class);
        var session = mock(InProcessAgentSession.class);
        var listeners = new ArrayList<AgentEventListener>();
        when(session.id()).thenReturn("context-1");
        doAnswer(invocation -> {
            listeners.add(invocation.getArgument(0));
            return null;
        }).when(session).onEvent(any());
        doAnswer(invocation -> {
            listeners.remove(invocation.getArgument(0));
            return null;
        }).when(session).removeEvent(any());
        when(manager.createSessionFromAgent(any(AgentDefinition.class), isNull(), eq("user-1"), eq("a2a")))
                .thenReturn(new AgentSessionManager.SessionCreationResult("context-1", List.of(), List.of()));
        when(manager.getSession("context-1")).thenReturn(session);
        service.sessionManager = manager;
        return new MockSession(session, listeners);
    }

    private void fire(List<AgentEventListener> listeners, Consumer<AgentEventListener> event) {
        for (var listener : List.copyOf(listeners)) {
            event.accept(listener);
        }
    }

    private AgentDefinition definition() {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.name = "reviewer";
        definition.description = "review code";
        definition.publishedAt = ZonedDateTime.parse("2026-05-06T00:00:00Z");
        var published = new AgentPublishedConfig();
        published.tools = List.of(
                ToolRef.of("jira-search", ToolSourceType.API),
                ToolRef.of("github-mcp", ToolSourceType.MCP, "github")
        );
        definition.publishedConfig = published;
        return definition;
    }

    private static final class FakeAgentDefinitionService extends AgentDefinitionService {
        private final AgentDefinition definition;

        FakeAgentDefinitionService(AgentDefinition definition) {
            this.definition = definition;
        }

        @Override
        public AgentDefinition getEntity(String id) {
            assertEquals(definition.id, id);
            return definition;
        }
    }

    private record MockSession(InProcessAgentSession session, List<AgentEventListener> listeners) {
    }
}
