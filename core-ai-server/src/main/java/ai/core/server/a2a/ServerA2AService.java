package ai.core.server.a2a;

import ai.core.a2a.A2AInvocationResult;
import ai.core.a2a.A2ATaskState;
import ai.core.api.a2a.AgentCard;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.session.AgentSessionManager;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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

    private final ServerA2ARouting routing = new ServerA2ARouting();
    private final ConcurrentMap<String, A2ATaskState> tasks = new ConcurrentHashMap<>();

    public void setTaskRouting(A2ATaskRegistry taskRegistry, SessionOwnershipRegistry ownershipRegistry,
                               RpcClient rpcClient, A2AEventRelay eventRelay) {
        routing.taskRegistry = taskRegistry;
        routing.ownershipRegistry = ownershipRegistry;
        routing.rpcClient = rpcClient;
        routing.eventRelay = eventRelay;
    }

    public AgentCard agentCard(String agentId) {
        return ServerA2AAgentCardFactory.from(agentDefinitionService.getEntity(agentId));
    }

    public A2AInvocationResult send(String agentId, SendMessageRequest request, String userId) {
        pruneTerminalTasks();
        validateMessageRequest(request);
        if (request.message.taskId != null && !request.message.taskId.isBlank()) {
            return A2AInvocationResult.ofTask(resumeTask(request.message, userId));
        }
        if (contextOwnedByAnotherPod(request.message.contextId)) {
            var returnImmediately = request.configuration != null && Boolean.TRUE.equals(request.configuration.returnImmediately);
            return A2AInvocationResult.ofTask(startTaskRemotely(agentId, request, userId, !returnImmediately));
        }
        if (request.configuration != null && Boolean.TRUE.equals(request.configuration.returnImmediately)) {
            var state = createTask(agentId, request, userId, ServerA2ATaskOptions.empty());
            return A2AInvocationResult.ofTask(state.toTask());
        }
        return A2AInvocationResult.ofTask(createSyncTask(agentId, request, userId));
    }

    public A2ATaskState stream(String agentId, SendMessageRequest request, String userId,
                               Consumer<StreamResponse> streamSender, Runnable closeStream) {
        pruneTerminalTasks();
        validateMessageRequest(request);
        if (request.message.taskId != null && !request.message.taskId.isBlank()) {
            return resumeTask(request.message, userId, streamSender, closeStream);
        }
        if (contextOwnedByAnotherPod(request.message.contextId)) {
            return streamTaskRemotely(agentId, request, userId, streamSender, closeStream);
        }
        return createTask(agentId, request, userId, ServerA2ATaskOptions.stream(streamSender, closeStream));
    }

    public Task getTask(GetTaskRequest request) {
        pruneTerminalTasks();
        var id = taskId(request);
        var task = tasks.get(id);
        if (task != null) {
            return task.toTask();
        }
        var snapshot = routing.taskRegistry != null ? routing.taskRegistry.get(id) : null;
        if (snapshot == null) {
            throw new NotFoundException("task not found");
        }
        return snapshot.toTask();
    }

    public Task cancelTask(CancelTaskRequest request) {
        pruneTerminalTasks();
        var id = taskId(request);
        var task = tasks.get(id);
        if (task != null) {
            if (task.isTerminal()) return task.toTask();
            return cancelLocalTask(task);
        }
        var snapshot = routing.taskRegistry != null ? routing.taskRegistry.get(id) : null;
        if (snapshot == null) throw new NotFoundException("task not found");
        if (snapshot.isTerminal()) return snapshot.toTask();
        return callTaskOwner(snapshot, SessionCommand.a2aCancelTask(snapshot.contextId, null, id, routing.rpcClient.newRequestId()));
    }

    private Task createSyncTask(String agentId, SendMessageRequest request, String userId) {
        return createSyncTask(null, agentId, request, userId);
    }

    private Task createSyncTask(String taskId, String agentId, SendMessageRequest request, String userId) {
        var future = new CompletableFuture<Task>();
        var state = createTask(agentId, request, userId, ServerA2ATaskOptions.sync(taskId, future));
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
                                    ServerA2ATaskOptions options) {
        var session = session(request.message.contextId, agentId, userId);
        var resolvedTaskId = options.taskId != null && !options.taskId.isBlank() ? options.taskId : UUID.randomUUID().toString();
        var state = new A2ATaskState(resolvedTaskId, session.id(), session);
        state.setState(TaskState.WORKING);
        tasks.put(resolvedTaskId, state);
        saveTask(state);

        var adapter = new ServerA2AEventAdapter(state, options, routing, sessionManager);
        state.attachEventListener(adapter);
        state.setStreamCloser(() -> {
            adapter.stopStreaming();
            if (options.closeStream != null) options.closeStream.run();
        });
        if (options.streamSender != null) {
            options.streamSender.accept(StreamResponse.ofTask(state.toTask()));
        }

        var userText = request.extractUserText();
        LOGGER.info("creating server A2A task, agentId={}, taskId={}, contextId={}", agentId, resolvedTaskId, session.id());
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

    private Task resumeTask(Message message, String userId) {
        var state = tasks.get(message.taskId);
        if (state != null) {
            return resumeLocalTask(state, message, null, null).toTask();
        }
        var snapshot = snapshot(message.taskId);
        return callTaskOwner(snapshot, SessionCommand.a2aResumeTask(snapshot.contextId, userId,
                JsonUtil.toJson(message), routing.rpcClient.newRequestId()));
    }

    private A2ATaskState resumeTask(Message message, String userId,
                                    Consumer<StreamResponse> streamSender, Runnable closeStream) {
        var state = tasks.get(message.taskId);
        if (state != null) {
            return resumeLocalTask(state, message, streamSender, closeStream);
        }
        var snapshot = snapshot(message.taskId);
        return streamResumeRemotely(snapshot, message, userId, streamSender, closeStream);
    }

    private A2ATaskState resumeLocalTask(A2ATaskState state, Message message,
                                         Consumer<StreamResponse> streamSender, Runnable closeStream) {
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
        saveTask(state);
        if (streamSender != null) {
            state.resumeStream(streamSender, closeStream);
            streamSender.accept(StreamResponse.ofTask(state.toTask()));
        }
        state.session.approveToolCall(resolvedCallId, approvalDecision);
        return state;
    }

    public Task startTaskOnOwner(A2AStartTaskCommandPayload payload, String userId) {
        if (payload == null || payload.request == null) throw new BadRequestException("request required");
        validateMessageRequest(payload.request);
        if (Boolean.TRUE.equals(payload.synchronous)) {
            return createSyncTask(payload.taskId, payload.agentId, payload.request, userId);
        }
        return createTask(payload.agentId, payload.request, userId, ServerA2ATaskOptions.taskId(payload.taskId)).toTask();
    }

    public Task cancelTaskOnOwner(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) throw new NotFoundException("task not found");
        return cancelLocalTask(state);
    }

    public Task resumeTaskOnOwner(Message message) {
        var state = tasks.get(message.taskId);
        if (state == null) throw new NotFoundException("task not found");
        return resumeLocalTask(state, message, null, null).toTask();
    }

    private Task cancelLocalTask(A2ATaskState task) {
        if (task.isTerminal()) return task.toTask();
        task.session.cancelTurn();
        task.setState(TaskState.CANCELED);
        task.clearAwait();
        task.detachEventListener();
        saveTask(task);
        return task.toTask();
    }

    private Task startTaskRemotely(String agentId, SendMessageRequest request, String userId, boolean synchronous) {
        var taskId = UUID.randomUUID().toString();
        var snapshot = remoteSnapshot(taskId, request.message.contextId);
        var payload = startTaskPayload(taskId, agentId, request, synchronous);
        var command = SessionCommand.a2aStartTask(snapshot.contextId, userId, JsonUtil.toJson(payload),
                routing.rpcClient.newRequestId());
        var timeout = synchronous ? Duration.ofMinutes(5) : Duration.ofSeconds(15);
        return callTaskOwner(snapshot, command, timeout);
    }

    private A2ATaskState streamTaskRemotely(String agentId, SendMessageRequest request, String userId,
                                            Consumer<StreamResponse> streamSender, Runnable closeStream) {
        var taskId = UUID.randomUUID().toString();
        var snapshot = remoteSnapshot(taskId, request.message.contextId);
        var payload = startTaskPayload(taskId, agentId, request, false);
        var command = SessionCommand.a2aStartTask(snapshot.contextId, userId, JsonUtil.toJson(payload),
                routing.rpcClient.newRequestId());
        proxyStream(snapshot, command, streamSender, closeStream);
        return null;
    }

    private A2ATaskState streamResumeRemotely(A2ATaskSnapshot snapshot, Message message, String userId,
                                              Consumer<StreamResponse> streamSender, Runnable closeStream) {
        var command = SessionCommand.a2aResumeTask(snapshot.contextId, userId, JsonUtil.toJson(message),
                routing.rpcClient.newRequestId());
        proxyStream(snapshot, command, streamSender, closeStream);
        return null;
    }

    private void proxyStream(A2ATaskSnapshot snapshot, SessionCommand command,
                             Consumer<StreamResponse> streamSender, Runnable closeStream) {
        A2AEventRelay.Subscription subscription = null;
        if (routing.eventRelay != null) {
            subscription = routing.eventRelay.subscribe(snapshot.taskId, streamSender, closeStream);
        }
        try {
            var task = callTaskOwner(snapshot, command, Duration.ofSeconds(15));
            streamSender.accept(StreamResponse.ofTask(task));
            if (routing.eventRelay == null && closeStream != null) closeStream.run();
        } catch (RuntimeException e) {
            if (subscription != null) subscription.close();
            throw e;
        }
    }

    private A2ATaskSnapshot remoteSnapshot(String taskId, String contextId) {
        var snapshot = new A2ATaskSnapshot();
        snapshot.taskId = taskId;
        snapshot.contextId = contextId;
        snapshot.ownerPod = routing.ownershipRegistry != null ? routing.ownershipRegistry.getOwner(contextId) : null;
        snapshot.state = TaskState.WORKING;
        snapshot.updatedAtMillis = System.currentTimeMillis();
        if (routing.taskRegistry != null) routing.taskRegistry.save(snapshot);
        return snapshot;
    }

    private A2AStartTaskCommandPayload startTaskPayload(String taskId, String agentId, SendMessageRequest request, boolean synchronous) {
        var payload = new A2AStartTaskCommandPayload();
        payload.taskId = taskId;
        payload.agentId = agentId;
        payload.request = request;
        payload.synchronous = synchronous;
        return payload;
    }

    private Task callTaskOwner(A2ATaskSnapshot snapshot, SessionCommand command) {
        return callTaskOwner(snapshot, command, Duration.ofSeconds(15));
    }

    private Task callTaskOwner(A2ATaskSnapshot snapshot, SessionCommand command, Duration timeout) {
        if (routing.rpcClient == null) throw new NotFoundException("task not found");
        return routing.rpcClient.callToPod(snapshot.ownerPod, command, Task.class, timeout);
    }

    private A2ATaskSnapshot snapshot(String taskId) {
        var snapshot = routing.taskRegistry != null ? routing.taskRegistry.get(taskId) : null;
        if (snapshot == null) throw new NotFoundException("task not found");
        return snapshot;
    }

    private boolean contextOwnedByAnotherPod(String contextId) {
        if (contextId == null || contextId.isBlank() || routing.ownershipRegistry == null) return false;
        var owner = routing.ownershipRegistry.getOwner(contextId);
        return owner != null && !owner.equals(routing.ownershipRegistry.getHostname());
    }

    private void saveTask(A2ATaskState state) {
        if (sessionManager != null) {
            sessionManager.touchSession(state.contextId);
        }
        if (routing.taskRegistry != null) {
            routing.taskRegistry.save(state);
        }
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
        saveTask(state);
    }

    void pruneTerminalTasks() {
        var cutoff = System.currentTimeMillis() - TERMINAL_TASK_TTL_MILLIS;
        tasks.entrySet().removeIf(entry -> entry.getValue().isTerminal()
                && entry.getValue().updatedAtMillis() < cutoff);
    }
}
