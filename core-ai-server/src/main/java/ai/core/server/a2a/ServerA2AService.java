package ai.core.server.a2a;

import ai.core.a2a.A2AEventAdapter;
import ai.core.a2a.A2AInvocationResult;
import ai.core.a2a.A2ATaskState;
import ai.core.api.a2a.AgentCard;
import ai.core.api.a2a.A2ATransport;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.session.AgentSessionManager;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Server-side A2A provider backed by core-ai-server agent sessions.
 *
 * @author xander
 */
public class ServerA2AService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerA2AService.class);
    private static final long TERMINAL_TASK_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static boolean isValidDecision(String decision) {
        return "approve".equalsIgnoreCase(decision) || "deny".equalsIgnoreCase(decision);
    }

    private static String extractDecision(Message msg) {
        var text = msg.extractText().trim().toLowerCase(Locale.ROOT);
        if ("approve".equals(text) || "allow".equals(text)) return "approve";
        if ("deny".equals(text) || "reject".equals(text)) return "deny";
        if (msg.parts == null) return null;
        for (var part : msg.parts) {
            if (!(part.data instanceof Map<?, ?> data)) continue;
            var decision = data.get("decision");
            if (decision != null) return String.valueOf(decision);
        }
        return null;
    }

    private static String extractCallId(Message msg) {
        if (msg.parts == null) return null;
        for (var part : msg.parts) {
            if (!(part.data instanceof Map<?, ?> data)) continue;
            var callId = data.get("call_id");
            if (callId != null) return String.valueOf(callId);
            callId = data.get("callId");
            if (callId != null) return String.valueOf(callId);
        }
        return null;
    }

    private static List<ToolRef> effectiveTools(AgentDefinition definition) {
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null) {
            return definition.publishedConfig.tools;
        }
        return definition.tools;
    }

    private static String taskId(GetTaskRequest request) {
        if (request == null || request.id == null || request.id.isBlank()) {
            throw new BadRequestException("taskId required");
        }
        return request.id;
    }

    private static String taskId(CancelTaskRequest request) {
        if (request == null || request.id == null || request.id.isBlank()) {
            throw new BadRequestException("taskId required");
        }
        return request.id;
    }

    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    AgentSessionManager sessionManager;

    private final ConcurrentMap<String, A2ATaskState> tasks = new ConcurrentHashMap<>();

    public AgentCard agentCard(String agentId) {
        var definition = agentDefinitionService.getEntity(agentId);
        var card = new AgentCard();
        card.name = definition.name;
        card.description = definition.description != null ? definition.description : "core-ai-server agent";
        card.version = definition.publishedAt != null ? definition.publishedAt.toInstant().toString() : "draft";
        var interfaceConfig = new AgentCard.AgentInterface();
        interfaceConfig.protocolBinding = A2ATransport.HTTP_JSON;
        interfaceConfig.protocolVersion = "1.0";
        interfaceConfig.url = "/api/a2a";
        interfaceConfig.tenant = agentId;
        card.supportedInterfaces = List.of(interfaceConfig);
        var capabilities = new AgentCard.AgentCapabilities();
        capabilities.streaming = true;
        capabilities.pushNotifications = false;
        capabilities.stateTransitionHistory = false;
        card.capabilities = capabilities;
        card.skills = skills(definition);
        card.defaultInputModes = List.of("text/plain", "application/json");
        card.defaultOutputModes = List.of("text/plain", "application/json");
        return card;
    }

    public A2AInvocationResult send(String agentId, SendMessageRequest request, String userId) {
        pruneTerminalTasks();
        validateMessageRequest(request);
        if (request.message.taskId != null && !request.message.taskId.isBlank()) {
            var state = resumeTask(request.message);
            return A2AInvocationResult.ofTask(state.toTask());
        }
        if (request.configuration != null && Boolean.TRUE.equals(request.configuration.returnImmediately)) {
            var state = createTask(agentId, request, userId, null, null, null);
            return A2AInvocationResult.ofTask(state.toTask());
        }
        return A2AInvocationResult.ofTask(createSyncTask(agentId, request, userId));
    }

    public A2ATaskState stream(String agentId, SendMessageRequest request, String userId,
                               Consumer<StreamResponse> streamSender, Runnable closeStream) {
        pruneTerminalTasks();
        validateMessageRequest(request);
        if (request.message.taskId != null && !request.message.taskId.isBlank()) {
            return resumeTask(request.message, streamSender, closeStream);
        }
        return createTask(agentId, request, userId, streamSender, closeStream, null);
    }

    public Task getTask(GetTaskRequest request) {
        pruneTerminalTasks();
        var task = tasks.get(taskId(request));
        if (task == null) {
            throw new NotFoundException("task not found");
        }
        return task.toTask();
    }

    public Task cancelTask(CancelTaskRequest request) {
        pruneTerminalTasks();
        var id = taskId(request);
        var task = tasks.get(id);
        if (task == null) {
            throw new NotFoundException("task not found");
        }
        task.session.cancelTurn();
        task.setState(TaskState.CANCELED);
        task.clearAwait();
        task.detachEventListener();
        return task.toTask();
    }

    private Task createSyncTask(String agentId, SendMessageRequest request, String userId) {
        var future = new CompletableFuture<Task>();
        var state = createTask(agentId, request, userId, null, null, future);
        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelAndFail(state, e);
            return state.toTask();
        } catch (TimeoutException e) {
            cancelAndFail(state, e);
            return state.toTask();
        } catch (Exception e) {
            fail(state, e);
            return state.toTask();
        }
    }

    private A2ATaskState createTask(String agentId, SendMessageRequest request, String userId,
                                    Consumer<StreamResponse> streamSender, Runnable closeStream,
                                    CompletableFuture<Task> syncFuture) {
        var session = session(request.message.contextId, agentId, userId);
        var taskId = UUID.randomUUID().toString();
        var state = new A2ATaskState(taskId, session.id(), session);
        state.setState(TaskState.WORKING);
        tasks.put(taskId, state);

        var adapter = new A2AEventAdapter(taskId, state, streamSender, syncFuture);
        state.attachEventListener(adapter);
        state.setStreamCloser(() -> {
            adapter.stopStreaming();
            if (closeStream != null) closeStream.run();
        });
        if (streamSender != null) {
            streamSender.accept(StreamResponse.ofTask(state.toTask()));
        }

        var userText = request.extractUserText();
        LOGGER.info("creating server A2A task, agentId={}, taskId={}, contextId={}", agentId, taskId, session.id());
        session.sendMessage(userText);
        return state;
    }

    private ai.core.api.server.session.AgentSession session(String contextId, String agentId, String userId) {
        if (contextId != null && !contextId.isBlank()) {
            return sessionManager.getSession(contextId);
        }
        var definition = agentDefinitionService.getEntity(agentId);
        var result = sessionManager.createSessionFromAgent(definition, null, userId, "a2a");
        return sessionManager.getSession(result.sessionId());
    }

    private A2ATaskState resumeTask(Message message) {
        return resumeTask(message, null, null);
    }

    private A2ATaskState resumeTask(Message message, Consumer<StreamResponse> streamSender, Runnable closeStream) {
        var state = tasks.get(message.taskId);
        if (state == null) {
            throw new NotFoundException("task not found");
        }
        if (state.getState() != TaskState.INPUT_REQUIRED) {
            throw new BadRequestException("task is not awaiting input");
        }
        var decision = extractDecision(message);
        if (!isValidDecision(decision)) {
            throw new BadRequestException("decision must be approve or deny");
        }
        var callId = extractCallId(message);
        var resolvedCallId = callId != null ? callId : state.getAwaitCallId();
        if (resolvedCallId == null) {
            throw new BadRequestException("callId required");
        }
        var approvalDecision = "deny".equalsIgnoreCase(decision) ? ApprovalDecision.DENY : ApprovalDecision.APPROVE;
        state.setState(TaskState.WORKING);
        state.clearAwait();
        if (streamSender != null) {
            state.resumeStream(streamSender, closeStream);
            streamSender.accept(StreamResponse.ofTask(state.toTask()));
        }
        state.session.approveToolCall(resolvedCallId, approvalDecision);
        return state;
    }

    private List<AgentCard.Skill> skills(AgentDefinition definition) {
        var skills = new ArrayList<AgentCard.Skill>();
        var primary = AgentCard.Skill.of(definition.id, definition.description != null ? definition.description : definition.name);
        primary.name = definition.name;
        primary.inputModes = List.of("text/plain", "application/json");
        primary.outputModes = List.of("text/plain", "application/json");
        skills.add(primary);
        var toolRefs = effectiveTools(definition);
        if (toolRefs != null) {
            for (var tool : toolRefs) {
                if (tool == null || tool.id == null) continue;
                var skill = AgentCard.Skill.of(tool.id, "Server-side tool: " + tool.id);
                skill.tags = tool.type != null ? List.of(tool.type.name()) : null;
                skills.add(skill);
            }
        }
        return skills;
    }

    private void validateMessageRequest(SendMessageRequest request) {
        if (request == null || request.message == null || request.message.parts == null || request.message.parts.isEmpty()) {
            throw new BadRequestException("message.parts required");
        }
    }

    private void cancelAndFail(A2ATaskState state, Exception e) {
        state.session.cancelTurn();
        fail(state, e);
    }

    private void fail(A2ATaskState state, Exception e) {
        state.setState(TaskState.FAILED);
        state.errorMessage = e.getMessage();
        state.clearAwait();
        state.detachEventListener();
    }

    void pruneTerminalTasks() {
        var cutoff = System.currentTimeMillis() - TERMINAL_TASK_TTL_MILLIS;
        tasks.entrySet().removeIf(entry -> entry.getValue().isTerminal()
                && entry.getValue().updatedAtMillis() < cutoff);
    }
}
