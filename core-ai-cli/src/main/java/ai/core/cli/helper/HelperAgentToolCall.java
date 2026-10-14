package ai.core.cli.helper;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.agenthub.AgentHubInputRequest;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.cli.hub.agent.AgentHubClient;
import ai.core.cli.http.RemoteApiException;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * One server agent mounted as a local delegation tool. Calls are messages on a per
 * (local session, helper) thread: the first call runs a task, follow-ups continue the same
 * server conversation, an {@code input_required} state is relayed to the model and the next
 * call answers the awaiting task through the hub reply endpoint.
 *
 * @author stephen
 */
public class HelperAgentToolCall extends ToolCall {
    static final int MAX_TASK_CHARS = 30_000;
    private static final long CALL_TIMEOUT_MS = 180_000L;
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_INPUT_REQUIRED = "input_required";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String LOCAL_SESSION_FALLBACK = "local";

    public static Builder builder() {
        return new Builder();
    }

    static String describe(HelperEntry helper) {
        var details = helper.description != null && !helper.description.isBlank() ? ": " + helper.description : "";
        var whenToUse = helper.whenToUse != null && !helper.whenToUse.isBlank() ? "\nUse it when: " + helper.whenToUse : "";
        return "Delegate to \"" + HelperEntry.displayName(helper) + "\" - a company agent running on core-ai-server"
                + details + "."
                + whenToUse
                + "\nSend a self-contained task with all needed context; follow-up calls continue the same conversation.";
    }

    private static String firstNonNull(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private HelperEntry helper;
    private String displayName;
    private Supplier<AgentHubClient> clientSupplier;
    private final Map<String, ThreadState> threads = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        var sessionId = localSessionId(context);
        try {
            var task = getStringValue(parseArguments(arguments), "task");
            var validation = validate(task);
            if (validation != null) {
                return validation.withDuration(System.currentTimeMillis() - startTime);
            }
            var client = clientSupplier.get();
            if (client == null) {
                return failure("Not signed in to core-ai-server; sign in before using helper '" + displayName + "'.")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            return call(sessionId, task, client).withDuration(System.currentTimeMillis() - startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("Helper '" + displayName + "' call interrupted", e).withDuration(System.currentTimeMillis() - startTime);
        } catch (RemoteApiException e) {
            return mapRemoteError(sessionId, e).withDuration(System.currentTimeMillis() - startTime);
        } catch (RuntimeException e) {
            return failure("Helper '" + displayName + "' call failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult call(String sessionId, String task, AgentHubClient client) throws InterruptedException {
        var gate = gates.computeIfAbsent(sessionId, ignored -> new Semaphore(1));
        gate.acquire();
        try {
            var state = threads.get(sessionId);
            if (state == null || state.lastTaskId() == null) {
                return runTask(sessionId, task, state == null ? null : state.contextId(), client);
            }
            if (STATUS_INPUT_REQUIRED.equals(state.lastState())) {
                return replyTask(sessionId, state.lastTaskId(), task, client);
            }
            if (STATUS_RUNNING.equals(state.lastState())) {
                return checkRunning(sessionId, state, client);
            }
            return runTask(sessionId, task, state.contextId(), client);
        } finally {
            gate.release();
        }
    }

    private ToolCallResult runTask(String sessionId, String task, String contextId, AgentHubClient client) {
        var request = new AgentHubRunRequest();
        request.task = task;
        request.contextId = contextId;
        return mapResult(sessionId, client.run(helper.agentId, request));
    }

    private ToolCallResult replyTask(String sessionId, String taskId, String message, AgentHubClient client) {
        var request = new AgentHubReplyRequest();
        request.message = message;
        return mapResult(sessionId, client.reply(taskId, request));
    }

    private ToolCallResult checkRunning(String sessionId, ThreadState state, AgentHubClient client) {
        var status = client.status(state.lastTaskId());
        if (STATUS_RUNNING.equals(status.status)) {
            return withStats(ToolCallResult.completed("Helper '" + displayName
                    + "' is still working on the previous task; ask again in a moment to collect the result."), status);
        }
        saveThread(sessionId, status);
        return withStats(ToolCallResult.completed("The previous task of helper '" + displayName + "' has finished.\n\n"
                + preview(status)
                + "\n\nIf your latest question has not been handled yet, call this tool again."), status);
    }

    private ToolCallResult mapResult(String sessionId, AgentHubRunResult result) {
        saveThread(sessionId, result);
        if (STATUS_COMPLETED.equals(result.status)) {
            var output = result.output != null ? result.output : "(no output)";
            if (Boolean.TRUE.equals(result.outputTruncated)) {
                output += "\n(output truncated)";
            }
            return withStats(ToolCallResult.completed(output), result);
        }
        if (STATUS_INPUT_REQUIRED.equals(result.status)) {
            return withStats(ToolCallResult.completed(inputRequiredText(result.inputRequest)), result);
        }
        if (STATUS_RUNNING.equals(result.status)) {
            return withStats(ToolCallResult.completed("Helper '" + displayName
                    + "' is still working; ask again in a moment to collect the result."), result);
        }
        if (STATUS_CANCELLED.equals(result.status)) {
            return withStats(ToolCallResult.failed("Helper task was cancelled: " + failureText(result)), result);
        }
        return withStats(ToolCallResult.failed("Helper '" + displayName + "' failed: " + failureText(result)), result);
    }

    private String inputRequiredText(AgentHubInputRequest request) {
        var tool = request != null && request.tool != null && !request.tool.isBlank() ? request.tool : "a tool";
        var arguments = request != null && request.arguments != null && !request.arguments.isBlank()
                ? "\nArguments: " + request.arguments : "";
        return "Helper '" + displayName + "' needs your confirmation before running '" + tool + "'."
                + arguments
                + "\nShow this to the user, then call this tool again with the user's answer (approve/deny or the requested information).";
    }

    private String preview(AgentHubRunResult result) {
        if (STATUS_COMPLETED.equals(result.status)) {
            return result.output != null ? result.output : "(no output)";
        }
        return failureText(result);
    }

    private String failureText(AgentHubRunResult result) {
        if (result.errorMessage != null && !result.errorMessage.isBlank()) {
            return result.errorMessage;
        }
        return result.errorCode != null ? "error: " + result.errorCode : "status: " + result.status;
    }

    private void saveThread(String sessionId, AgentHubRunResult result) {
        var previous = threads.get(sessionId);
        var contextId = firstNonNull(result.contextId, previous == null ? null : previous.contextId());
        var taskId = firstNonNull(result.taskId, previous == null ? null : previous.lastTaskId());
        if (contextId == null && taskId == null) {
            return;
        }
        threads.put(sessionId, new ThreadState(contextId, taskId, result.status));
    }

    private ToolCallResult validate(String task) {
        if (task == null || task.isBlank()) {
            return failure("Parameter 'task' is required for helper '" + displayName + "'.");
        }
        if (task.length() > MAX_TASK_CHARS) {
            return failure("Helper input is too large (" + task.length() + " chars). Summarize it under "
                    + MAX_TASK_CHARS + " characters and retry.");
        }
        return null;
    }

    private ToolCallResult mapRemoteError(String sessionId, RemoteApiException e) {
        if (e.statusCode == 401) {
            return failure("Not signed in to core-ai-server; sign in and try again.");
        }
        if (e.statusCode == 404) {
            threads.remove(sessionId);
            return failure("Helper '" + displayName + "' or its task is no longer available "
                    + "(removed, unpublished or expired); the next call starts a new conversation.");
        }
        if (e.statusCode == 429) {
            return failure("Helper quota exceeded: " + e.getMessage());
        }
        return failure("Helper call failed (HTTP " + e.statusCode + "): " + e.getMessage());
    }

    private ToolCallResult failure(String message) {
        return ToolCallResult.failed(message).withToolName(getName());
    }

    private ToolCallResult failure(String message, Throwable cause) {
        return ToolCallResult.failed(message, cause).withToolName(getName());
    }

    private ToolCallResult withStats(ToolCallResult result, AgentHubRunResult hubResult) {
        return result.withToolName(getName())
                .withStats("helper_agent_id", helper.agentId)
                .withStats("helper_task_id", hubResult.taskId)
                .withStats("helper_context_id", hubResult.contextId)
                .withStats("helper_state", hubResult.status);
    }

    private String localSessionId(ExecutionContext context) {
        if (context != null && context.getSessionId() != null && !context.getSessionId().isBlank()) {
            return context.getSessionId();
        }
        return LOCAL_SESSION_FALLBACK;
    }

    private record ThreadState(String contextId, String lastTaskId, String lastState) {
    }

    public static class Builder extends ToolCall.Builder<Builder, HelperAgentToolCall> {
        private HelperEntry helper;
        private Supplier<AgentHubClient> clientSupplier;

        public Builder helper(HelperEntry helper) {
            this.helper = helper;
            return self();
        }

        public Builder clientSupplier(Supplier<AgentHubClient> clientSupplier) {
            this.clientSupplier = clientSupplier;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public HelperAgentToolCall build() {
            if (helper == null) throw new IllegalArgumentException("helper is required");
            if (clientSupplier == null) throw new IllegalArgumentException("clientSupplier is required");
            description(describe(helper));
            timeoutMs(CALL_TIMEOUT_MS);
            parameters(List.of(ToolCallParameter.builder()
                    .name("task")
                    .description("The task or question to send to this server agent. Include all context it "
                            + "needs; follow-up calls continue the same conversation.")
                    .type(ToolCallParameterType.STRING)
                    .classType(String.class)
                    .required(Boolean.TRUE)
                    .build()));
            var tool = new HelperAgentToolCall();
            super.build(tool);
            tool.helper = helper;
            tool.displayName = HelperEntry.displayName(helper);
            tool.clientSupplier = clientSupplier;
            return tool;
        }
    }
}
