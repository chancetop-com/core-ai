package ai.core.server.a2a;

import ai.core.a2a.A2ATaskState;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.server.messaging.SessionCommand;
import ai.core.utils.JsonUtil;
import core.framework.web.exception.NotFoundException;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Cross-pod half of the A2A task lifecycle: a conversation belongs to the pod its session lives on,
 * so every other pod forwards commands to that owner and reads the shared task snapshots.
 * <p>
 * Forwarding also registers a local snapshot of the task, so a caller on this pod can poll a task
 * that the owner pod executes.
 *
 * @author xander
 */
final class A2ARemoteTaskGateway {
    static final Duration DELEGATE_TIMEOUT = Duration.ofSeconds(15);

    private final ServerA2AService service;

    A2ARemoteTaskGateway(ServerA2AService service) {
        this.service = service;
    }

    boolean ownedByAnotherPod(String contextId) {
        if (contextId == null || contextId.isBlank() || service.ownershipRegistry == null) return false;
        var owner = service.ownershipRegistry.getOwner(contextId);
        return owner != null && !owner.equals(service.ownershipRegistry.getHostname());
    }

    Task start(String agentId, SendMessageRequest request, String userId, boolean synchronous) {
        return start(agentId, request, userId, synchronous, ServerA2ATaskOptions.DEFAULT_WAIT_TIMEOUT);
    }

    Task start(String agentId, SendMessageRequest request, String userId, boolean synchronous, Duration waitTimeout) {
        var taskId = UUID.randomUUID().toString();
        var snapshot = register(taskId, request.message.contextId);
        var command = SessionCommand.a2aStartTask(snapshot.contextId, userId,
                JsonUtil.toJson(startPayload(taskId, agentId, request, synchronous)), service.rpcClient.newRequestId());
        return call(snapshot, command, synchronous ? waitTimeout : DELEGATE_TIMEOUT);
    }

    /**
     * Starts a forwarded task for a non-A2A caller: a timeout does not fail the call, the caller
     * polls the local snapshot of the task the owner pod keeps running.
     */
    A2ATaskView startForCaller(String agentId, SendMessageRequest request, String userId, Duration waitTimeout) {
        var taskId = UUID.randomUUID().toString();
        var snapshot = register(taskId, request.message.contextId);
        var command = SessionCommand.a2aStartTask(snapshot.contextId, userId,
                JsonUtil.toJson(startPayload(taskId, agentId, request, true)), service.rpcClient.newRequestId());
        try {
            call(snapshot, command, waitTimeout);
        } catch (RuntimeException e) {
            var current = get(taskId);
            if (current == null) throw e;
            return A2ATaskView.of(current);
        }
        var updated = get(taskId);
        return updated != null ? A2ATaskView.of(updated) : A2ATaskView.ofTask(snapshot.toTask());
    }

    A2ATaskState streamStart(String agentId, SendMessageRequest request, String userId,
                             Consumer<StreamResponse> streamSender, Runnable closeStream) {
        var taskId = UUID.randomUUID().toString();
        var snapshot = register(taskId, request.message.contextId);
        var command = SessionCommand.a2aStartTask(snapshot.contextId, userId,
                JsonUtil.toJson(startPayload(taskId, agentId, request, false)), service.rpcClient.newRequestId());
        proxyStream(snapshot, command, streamSender, closeStream);
        return null;
    }

    A2ATaskState streamResume(A2ATaskSnapshot snapshot, Message message, String userId,
                              Consumer<StreamResponse> streamSender, Runnable closeStream) {
        var command = SessionCommand.a2aResumeTask(snapshot.contextId, userId, JsonUtil.toJson(message),
                service.rpcClient.newRequestId());
        proxyStream(snapshot, command, streamSender, closeStream);
        return null;
    }

    Task resume(A2ATaskSnapshot snapshot, Message message, String userId) {
        return call(snapshot, SessionCommand.a2aResumeTask(snapshot.contextId, userId,
                JsonUtil.toJson(message), service.rpcClient.newRequestId()));
    }

    Task cancel(A2ATaskSnapshot snapshot) {
        return call(snapshot, SessionCommand.a2aCancelTask(snapshot.contextId, null, snapshot.taskId,
                service.rpcClient.newRequestId()));
    }

    A2ATaskSnapshot get(String taskId) {
        return service.taskRegistry != null ? service.taskRegistry.get(taskId) : null;
    }

    A2ATaskSnapshot require(String taskId) {
        var snapshot = get(taskId);
        if (snapshot == null) throw new NotFoundException("task not found");
        return snapshot;
    }

    Task call(A2ATaskSnapshot snapshot, SessionCommand command) {
        return call(snapshot, command, DELEGATE_TIMEOUT);
    }

    Task call(A2ATaskSnapshot snapshot, SessionCommand command, Duration timeout) {
        if (service.rpcClient == null) throw new NotFoundException("task not found");
        return service.rpcClient.callToPod(snapshot.ownerPod, command, Task.class, timeout);
    }

    private void proxyStream(A2ATaskSnapshot snapshot, SessionCommand command,
                             Consumer<StreamResponse> streamSender, Runnable closeStream) {
        A2AEventRelay.Subscription subscription = null;
        if (service.eventRelay != null) {
            subscription = service.eventRelay.subscribe(snapshot.taskId, streamSender, closeStream);
        }
        try {
            var task = call(snapshot, command, DELEGATE_TIMEOUT);
            streamSender.accept(StreamResponse.ofTask(task));
            if (service.eventRelay == null && closeStream != null) closeStream.run();
        } catch (RuntimeException e) {
            if (subscription != null) subscription.close();
            throw e;
        }
    }

    /** Registers the local view of a task that another pod executes. */
    private A2ATaskSnapshot register(String taskId, String contextId) {
        var snapshot = new A2ATaskSnapshot();
        snapshot.taskId = taskId;
        snapshot.contextId = contextId;
        snapshot.ownerPod = service.ownershipRegistry != null ? service.ownershipRegistry.getOwner(contextId) : null;
        snapshot.state = TaskState.WORKING;
        snapshot.updatedAtMillis = System.currentTimeMillis();
        if (service.taskRegistry != null) service.taskRegistry.save(snapshot);
        return snapshot;
    }

    private A2AStartTaskCommandPayload startPayload(String taskId, String agentId, SendMessageRequest request, boolean synchronous) {
        var payload = new A2AStartTaskCommandPayload();
        payload.taskId = taskId;
        payload.agentId = agentId;
        payload.request = request;
        payload.synchronous = synchronous;
        return payload;
    }
}
