package ai.core.server.a2a;

import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.Part;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.server.session.AgentSessionManager;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The A2A task surface for callers that are not speaking the A2A protocol — the Agent Hub, and any
 * future in-process caller. It keeps three properties the protocol path does not need:
 * <ul>
 *   <li>the wait budget belongs to the caller, and a timed-out run is left running instead of
 *       being cancelled ({@code status=running}, then poll {@link #viewTask});</li>
 *   <li>every operation is owner-scoped, so a task id is only usable by the user that created the
 *       conversation;</li>
 *   <li>a reply to a finished task continues the same conversation ({@code context_id}) as a new
 *       turn, which is how a caller holds a multi-turn dialogue with an agent.</li>
 * </ul>
 *
 * @author xander
 */
public class ServerA2ACallerService {
    private static final long POLL_INTERVAL_MILLIS = 500;
    private static final String SOURCE_HUB = "hub";

    @Inject
    ServerA2AService a2aService;
    @Inject
    AgentSessionManager sessionManager;

    /**
     * Sends a task on behalf of a caller: the wait behaviour is tunable and a timed-out run stays
     * alive for {@link #viewTask} to pick up.
     */
    public A2ATaskView sendForCaller(String agentId, SendMessageRequest request, String userId, ServerA2ATaskOptions options) {
        a2aService.pruneTerminalTasks();
        a2aService.validateMessageRequest(request);
        a2aService.checkCanRun(userId, agentId);
        boolean local = !a2aService.remote().ownedByAnotherPod(request.message.contextId);
        if (options.isDetached()) {
            return local ? A2ATaskView.of(a2aService.createTask(agentId, request, userId, options))
                    : A2ATaskView.ofTask(a2aService.remote().start(agentId, request, userId, false));
        }
        return local ? createWaitedTask(agentId, request, userId, options)
                : a2aService.remote().startForCaller(agentId, request, userId, options.effectiveWaitTimeout());
    }

    /** Owner-scoped task view. */
    public A2ATaskView viewTask(String taskId, String userId) {
        a2aService.pruneTerminalTasks();
        if (taskId == null || taskId.isBlank()) throw new BadRequestException("taskId required");
        var state = a2aService.localTask(taskId);
        if (state != null) {
            sessionManager.requireSessionOwner(state.contextId, userId);
            return A2ATaskView.of(state);
        }
        var snapshot = a2aService.snapshot(taskId);
        sessionManager.requireSessionOwner(snapshot.contextId, userId);
        return A2ATaskView.of(snapshot);
    }

    /**
     * Owner-scoped reply. An {@code input_required} task is answered with a tool approval decision,
     * any other terminal task continues its conversation as a new turn on the same context; a task
     * that is still running is rejected (409).
     */
    public A2ATaskView replyForCaller(String taskId, String userId, String decision, String message, Duration waitTimeout) {
        a2aService.pruneTerminalTasks();
        var state = a2aService.localTask(taskId);
        if (state != null) {
            sessionManager.requireSessionOwner(state.contextId, userId);
            if (state.getState() == TaskState.INPUT_REQUIRED) {
                a2aService.resumeLocalTask(state, approvalMessage(decision, message, state.getAwaitCallId()), null, null);
                return awaitTerminal(taskId, userId, waitTimeout);
            }
            if (!state.isTerminal()) throw new ConflictException("task is still running");
            return continueConversation(state.contextId, userId, message, waitTimeout);
        }
        var snapshot = a2aService.snapshot(taskId);
        sessionManager.requireSessionOwner(snapshot.contextId, userId);
        if (snapshot.state == TaskState.INPUT_REQUIRED) {
            a2aService.remote().resume(snapshot, approvalMessage(decision, message, snapshot.awaitCallId), userId);
            return awaitTerminal(taskId, userId, waitTimeout);
        }
        if (!snapshot.isTerminal()) throw new ConflictException("task is still running");
        return continueConversation(snapshot.contextId, userId, message, waitTimeout);
    }

    /** Owner-scoped cancel, mirroring the task view contract of {@link #viewTask}. */
    public A2ATaskView cancelForCaller(String taskId, String userId) {
        var view = viewTask(taskId, userId);
        if (view.task().status != null && TaskState.COMPLETED == view.task().status.state) {
            return view;
        }
        var request = new CancelTaskRequest();
        request.id = taskId;
        a2aService.cancelTask(request);
        return viewTask(taskId, userId);
    }

    /** Local waited run for a non-A2A caller: a timeout reports the task as running. */
    private A2ATaskView createWaitedTask(String agentId, SendMessageRequest request, String userId,
                                         ServerA2ATaskOptions options) {
        var future = new CompletableFuture<Task>();
        var state = a2aService.createTask(agentId, request, userId, ServerA2ATaskOptions.sync(null, future).source(options.effectiveSource()));
        try {
            future.get(options.effectiveWaitTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            a2aService.cancelAndFail(state, e);
        } catch (TimeoutException ignored) {
            // the run continues: the caller polls the task view
        } catch (Exception e) {
            a2aService.fail(state, e);
        }
        return A2ATaskView.of(state);
    }

    private A2ATaskView continueConversation(String contextId, String userId, String message, Duration waitTimeout) {
        if (message == null || message.isBlank()) {
            throw new BadRequestException("message required to continue the conversation");
        }
        var request = new SendMessageRequest();
        request.message = Message.user(message);
        request.message.contextId = contextId;
        return sendForCaller(sessionManager.sessionAgentId(contextId), request, userId,
                ServerA2ATaskOptions.wait(waitTimeout).source(SOURCE_HUB));
    }

    private A2ATaskView awaitTerminal(String taskId, String userId, Duration waitTimeout) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        var view = viewTask(taskId, userId);
        while (!isTerminal(view) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            view = viewTask(taskId, userId);
        }
        return view;
    }

    private boolean isTerminal(A2ATaskView view) {
        var state = view.task().status != null ? view.task().status.state : null;
        return state == TaskState.COMPLETED || state == TaskState.FAILED
                || state == TaskState.CANCELED || state == TaskState.REJECTED;
    }

    private Message approvalMessage(String decision, String message, String callId) {
        var text = message != null && !message.isBlank() ? message : decision != null ? decision : "";
        var resolved = Message.user(text);
        Map<String, Object> data = new HashMap<>();
        if (decision != null && !decision.isBlank()) data.put("decision", decision);
        if (callId != null) data.put("call_id", callId);
        if (!data.isEmpty()) {
            var parts = new ArrayList<>(resolved.parts);
            parts.add(Part.data(data));
            resolved.parts = parts;
        }
        return resolved;
    }
}
