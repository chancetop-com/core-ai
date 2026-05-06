package ai.core.a2a;

import ai.core.agent.Agent;
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
import ai.core.session.FileSessionPersistence;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class A2ARunManager implements A2AAgentProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2ARunManager.class);

    private static String truncate(String text) {
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    private static String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId required");
        }
        return taskId;
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

    private static boolean isValidDecision(String decision) {
        return "approve".equalsIgnoreCase(decision) || "deny".equalsIgnoreCase(decision);
    }

    private final Supplier<Agent> agentFactory;
    private final boolean autoApproveAll;
    private final ToolPermissionStore permissionStore;
    private final ConcurrentMap<String, A2ATaskState> tasks = new ConcurrentHashMap<>();
    private final String persistentSessionId;
    private InProcessAgentSession persistentSession;

    public A2ARunManager(Supplier<Agent> agentFactory) {
        this(agentFactory, false, null, null);
    }

    public A2ARunManager(Supplier<Agent> agentFactory, String sessionId) {
        this(agentFactory, false, null, sessionId);
    }

    public A2ARunManager(Supplier<Agent> agentFactory, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this(agentFactory, autoApproveAll, permissionStore, null);
    }

    public A2ARunManager(Supplier<Agent> agentFactory, boolean autoApproveAll, ToolPermissionStore permissionStore, String sessionId) {
        this.agentFactory = agentFactory;
        this.autoApproveAll = autoApproveAll;
        this.permissionStore = permissionStore;
        this.persistentSessionId = sessionId;
        if (sessionId != null) {
            this.persistentSession = createSession(sessionId);
        }
    }

    @Override
    public AgentCard getAgentCard() {
        var card = new AgentCard();
        card.name = "core-ai";
        card.description = "AI coding assistant with file operations and shell access";
        card.version = "1.0.0";
        var interfaceConfig = new AgentCard.AgentInterface();
        interfaceConfig.protocolBinding = A2ATransport.HTTP_JSON;
        interfaceConfig.protocolVersion = "1.0";
        card.supportedInterfaces = List.of(interfaceConfig);
        var caps = new AgentCard.AgentCapabilities();
        caps.streaming = true;
        caps.pushNotifications = false;
        card.capabilities = caps;
        card.skills = List.of(
                AgentCard.Skill.of("code-generation", "Generate and modify code"),
                AgentCard.Skill.of("file-operations", "Read, write, search files"),
                AgentCard.Skill.of("shell-execution", "Execute shell commands")
        );
        card.defaultInputModes = List.of("text/plain");
        card.defaultOutputModes = List.of("text/plain");
        return card;
    }

    @Override
    public A2AInvocationResult send(SendMessageRequest request) {
        if (request != null && request.message != null && request.message.taskId != null && !request.message.taskId.isBlank()) {
            var decision = extractDecision(request.message);
            if (!isValidDecision(decision)) {
                throw new IllegalArgumentException("decision must be approve or deny");
            }
            resumeTask(request.message.taskId, decision, extractCallId(request.message));
            return A2AInvocationResult.ofTask(getTask(request.message.taskId));
        }
        if (request != null && request.configuration != null && Boolean.TRUE.equals(request.configuration.returnImmediately)) {
            return A2AInvocationResult.ofTask(createStreamingTaskEvents(request, null).toTask());
        }
        return A2AInvocationResult.ofTask(createSyncTask(request));
    }

    @Override
    public Flow.Publisher<A2AStreamEvent> stream(SendMessageRequest request) {
        return new A2AStreamPublisher(sender -> createStreamingTaskEvents(request, sender));
    }

    public Task createSyncTask(SendMessageRequest request) {
        var contextId = request != null && request.message != null && request.message.contextId != null
                ? request.message.contextId
                : persistentSessionId;
        var session = getOrCreateSession(contextId);
        var taskId = UUID.randomUUID().toString();
        var state = new A2ATaskState(taskId, session.id(), session);
        state.setState(TaskState.WORKING);
        tasks.put(taskId, state);

        var future = new CompletableFuture<Task>();
        var adapter = new A2AEventAdapter(taskId, state, null, future);
        state.attachEventListener(adapter);

        var userText = request != null ? request.extractUserText() : "";
        LOGGER.info("creating sync task, taskId={}, message={}", taskId, truncate(userText));
        session.sendMessage(userText);

        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.setState(TaskState.FAILED);
            state.errorMessage = e.getMessage();
            state.detachEventListener();
            return state.toTask();
        } catch (Exception e) {
            state.setState(TaskState.FAILED);
            state.errorMessage = e.getMessage();
            state.detachEventListener();
            return state.toTask();
        }
    }

    public A2ATaskState createStreamingTask(SendMessageRequest request, Consumer<String> sseSender) {
        Consumer<StreamResponse> streamSender = sseSender == null ? null : response -> sseSender.accept(JsonUtil.toJson(response));
        return createStreamingTaskEvents(request, streamSender);
    }

    public A2ATaskState createStreamingTaskEvents(SendMessageRequest request, Consumer<StreamResponse> streamSender) {
        var contextId = request != null && request.message != null && request.message.contextId != null
                ? request.message.contextId
                : persistentSessionId;
        var session = getOrCreateSession(contextId);
        var taskId = UUID.randomUUID().toString();
        var state = new A2ATaskState(taskId, session.id(), session);
        state.setState(TaskState.WORKING);
        tasks.put(taskId, state);

        var adapter = new A2AEventAdapter(taskId, state, streamSender, null);
        state.attachEventListener(adapter);
        state.setStreamCloser(adapter::stopStreaming);
        if (streamSender != null) {
            streamSender.accept(StreamResponse.ofTask(state.toTask()));
        }

        var userText = request != null ? request.extractUserText() : "";
        LOGGER.info("creating streaming task, taskId={}, message={}", taskId, truncate(userText));
        session.sendMessage(userText);

        return state;
    }

    private InProcessAgentSession getOrCreateSession(String contextId) {
        if (persistentSession != null) {
            return persistentSession;
        }
        return createSession(contextId != null ? contextId : UUID.randomUUID().toString());
    }

    private InProcessAgentSession createSession(String sessionId) {
        var agent = agentFactory.get();
        return new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
    }

    public Task getTask(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) return null;
        return state.toTask();
    }

    @Override
    public Task getTask(GetTaskRequest request) {
        return getTask(requireTaskId(request != null ? request.id : null));
    }

    public void resumeTask(String taskId, String decision, String callId) {
        var state = tasks.get(taskId);
        if (state == null) throw new IllegalArgumentException("task not found: " + taskId);
        if (state.getState() != TaskState.INPUT_REQUIRED) throw new IllegalStateException("task is not awaiting input: " + taskId);

        var resolvedCallId = callId != null ? callId : state.getAwaitCallId();
        if (resolvedCallId == null) throw new IllegalStateException("no callId available for resume");

        var approvalDecision = "deny".equalsIgnoreCase(decision) ? ApprovalDecision.DENY : ApprovalDecision.APPROVE;
        LOGGER.info("resuming task, taskId={}, callId={}, decision={}", taskId, resolvedCallId, approvalDecision);

        state.setState(TaskState.WORKING);
        state.clearAwait();
        state.session.approveToolCall(resolvedCallId, approvalDecision);
    }

    public void cancelTask(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) throw new IllegalArgumentException("task not found: " + taskId);
        LOGGER.info("cancelling task, taskId={}", taskId);
        state.session.cancelTurn();
        state.setState(TaskState.CANCELED);
        state.clearAwait();
        state.detachEventListener();
    }

    @Override
    public Task cancelTask(CancelTaskRequest request) {
        var taskId = requireTaskId(request != null ? request.id : null);
        cancelTask(taskId);
        return getTask(taskId);
    }

    public void close() {
        if (persistentSession != null) {
            try {
                persistentSession.close();
            } catch (Exception e) {
                LOGGER.debug("failed to close persistent session", e);
            }
        }
        for (var state : tasks.values()) {
            try {
                state.session.close();
            } catch (Exception e) {
                LOGGER.debug("failed to close session, taskId={}", state.taskId, e);
            }
        }
        tasks.clear();
    }

    public String getSessionId() {
        return persistentSessionId;
    }

    public List<SessionPersistence.SessionInfo> listSessions(FileSessionPersistence sessionPersistence) {
        return sessionPersistence.listSessions();
    }

    public Supplier<Agent> getAgentFactory() {
        return agentFactory;
    }
}
