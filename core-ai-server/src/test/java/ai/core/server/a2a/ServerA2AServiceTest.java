package ai.core.server.a2a;

import ai.core.api.a2a.Part;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.SendMessageConfiguration;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
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
import ai.core.server.messaging.CommandType;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.session.AgentSessionManager;
import ai.core.session.InProcessAgentSession;
import ai.core.utils.JsonUtil;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void cancelCompletedLocalTaskKeepsTerminalState() {
        var service = new ServerA2AService();
        service.agentDefinitionService = new FakeAgentDefinitionService(definition());
        var session = mockSession(service);
        var state = service.stream("agent-1", request("hello"), "user-1", event -> {
        }, () -> {
        });
        fire(session.listeners, listener -> listener.onTextChunk(TextChunkEvent.of("context-1", "done")));
        fire(session.listeners, listener -> listener.onTurnComplete(TurnCompleteEvent.of("context-1", "done")));
        var request = new CancelTaskRequest();
        request.id = state.taskId;

        var task = service.cancelTask(request);

        assertEquals(TaskState.COMPLETED, task.status.state);
        assertEquals(TaskState.COMPLETED, state.getState());
        verify(session.session, never()).cancelTurn();
    }

    @Test
    void getTaskFallsBackToSharedSnapshot() {
        var service = new ServerA2AService();
        var registry = new FakeTaskRegistry();
        registry.snapshots.put("task-1", snapshot("task-1", "context-1", "pod-a", TaskState.COMPLETED, "done"));
        service.setTaskRouting(registry, null, null, null);
        var request = new GetTaskRequest();
        request.id = "task-1";

        var task = service.getTask(request);

        assertEquals("task-1", task.id);
        assertEquals("context-1", task.contextId);
        assertEquals(TaskState.COMPLETED, task.status.state);
        assertEquals("done", task.artifacts.getFirst().parts.getFirst().text);
    }

    @Test
    void cancelTaskForwardsToOwnerPodWhenTaskIsNotLocal() {
        var service = new ServerA2AService();
        var registry = new FakeTaskRegistry();
        registry.snapshots.put("task-1", snapshot("task-1", "context-1", "owner-pod", TaskState.WORKING, null));
        var rpcClient = mock(RpcClient.class);
        when(rpcClient.newRequestId()).thenReturn("req-1");
        var canceled = new Task();
        canceled.id = "task-1";
        canceled.status = ai.core.api.a2a.TaskStatus.of(TaskState.CANCELED);
        when(rpcClient.callToPod(eq("owner-pod"), any(SessionCommand.class), same(Task.class), any(Duration.class)))
                .thenReturn(canceled);
        service.setTaskRouting(registry, null, rpcClient, null);
        var request = new CancelTaskRequest();
        request.id = "task-1";

        var task = service.cancelTask(request);

        assertEquals(TaskState.CANCELED, task.status.state);
        verify(rpcClient).callToPod(eq("owner-pod"), argThat(command ->
                        command.type() == CommandType.A2A_CANCEL_TASK
                                && "context-1".equals(command.sessionId())
                                && "req-1".equals(command.requestId())
                                && command.payload().contains("task-1")),
                same(Task.class), any(Duration.class));
    }

    @Test
    void sendWithRemoteContextStartsTaskOnOwnerPod() {
        var service = new ServerA2AService();
        var registry = new FakeTaskRegistry();
        var ownership = mock(SessionOwnershipRegistry.class);
        when(ownership.getOwner("context-1")).thenReturn("owner-pod");
        when(ownership.getHostname()).thenReturn("gateway-pod");
        var rpcClient = mock(RpcClient.class);
        when(rpcClient.newRequestId()).thenReturn("req-1");
        var remoteTask = new Task();
        remoteTask.id = "remote-task";
        remoteTask.contextId = "context-1";
        remoteTask.status = ai.core.api.a2a.TaskStatus.of(TaskState.WORKING);
        when(rpcClient.callToPod(eq("owner-pod"), any(SessionCommand.class), same(Task.class), any(Duration.class)))
                .thenReturn(remoteTask);
        service.setTaskRouting(registry, ownership, rpcClient, null);
        var request = request("hello");
        request.message.contextId = "context-1";
        var config = new SendMessageConfiguration();
        config.returnImmediately = true;
        request.configuration = config;

        var result = service.send("agent-1", request, "user-1");

        assertEquals("remote-task", result.task.id);
        verify(rpcClient).callToPod(eq("owner-pod"), argThat(command -> {
            if (command.type() != CommandType.A2A_START_TASK || !"context-1".equals(command.sessionId())) return false;
            var payload = JsonUtil.fromJson(A2AStartTaskCommandPayload.class, command.payload());
            return "agent-1".equals(payload.agentId)
                    && "context-1".equals(payload.request.message.contextId)
                    && Boolean.FALSE.equals(payload.synchronous);
        }), same(Task.class), any(Duration.class));
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

    private A2ATaskSnapshot snapshot(String taskId, String contextId, String ownerPod, TaskState state, String output) {
        var snapshot = new A2ATaskSnapshot();
        snapshot.taskId = taskId;
        snapshot.contextId = contextId;
        snapshot.ownerPod = ownerPod;
        snapshot.state = state;
        snapshot.output = output;
        snapshot.updatedAtMillis = System.currentTimeMillis();
        return snapshot;
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

    private static final class FakeTaskRegistry extends A2ATaskRegistry {
        final Map<String, A2ATaskSnapshot> snapshots = new HashMap<>();

        @Override
        public void save(ai.core.a2a.A2ATaskState state) {
            snapshots.put(state.taskId, A2ATaskSnapshot.from(state, "owner-pod"));
        }

        @Override
        public void save(A2ATaskSnapshot snapshot) {
            snapshots.put(snapshot.taskId, snapshot);
        }

        @Override
        public A2ATaskSnapshot get(String taskId) {
            return snapshots.get(taskId);
        }
    }
}
