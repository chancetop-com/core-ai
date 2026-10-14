package ai.core.cli.helper;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.agenthub.AgentHubAttachment;
import ai.core.api.server.agenthub.AgentHubInputRequest;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.cli.hub.agent.AgentHubClient;
import ai.core.cli.hub.agent.LocalAttachmentUploader;
import ai.core.cli.http.RemoteApiException;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * One server agent mounted as a local delegation tool. Calls are messages on a per
 * (local session, helper) thread: the first call runs a task, follow-ups continue the same
 * server conversation, an {@code input_required} state is relayed to the model and the next
 * call answers the awaiting task through the hub reply endpoint. Attachments of the current
 * user message ride along with each task's first run on the thread.
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
                + "\nSend a self-contained task with all needed context; follow-up calls continue the same conversation."
                + "\nFiles attached to the user's current message are passed to this agent automatically; there is no need"
                + " to extract or paste their content.";
    }

    private static String firstNonNull(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private HelperEntry helper;
    private String displayName;
    private Supplier<AgentHubClient> clientSupplier;
    private Supplier<LocalAttachmentUploader> uploaderSupplier;
    private final Map<String, ThreadState> threads = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();
    /** Attachment delivery keys already sent to this helper per local session, so a turn's file goes once. */
    private final Map<String, Set<String>> deliveredAttachments = new ConcurrentHashMap<>();
    /** Upload results by content hash, so repeat forwards of the same bytes reuse one blob. */
    private final Map<String, AgentHubAttachment> uploadedAttachments = new ConcurrentHashMap<>();

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
            return call(sessionId, task, context, client).withDuration(System.currentTimeMillis() - startTime);
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

    private ToolCallResult call(String sessionId, String task, ExecutionContext context, AgentHubClient client) throws InterruptedException {
        var gate = gates.computeIfAbsent(sessionId, ignored -> new Semaphore(1));
        gate.acquire();
        try {
            var state = threads.get(sessionId);
            if (state == null || state.lastTaskId() == null) {
                return runTask(sessionId, task, state == null ? null : state.contextId(), context, client);
            }
            if (STATUS_INPUT_REQUIRED.equals(state.lastState())) {
                return replyTask(sessionId, state.lastTaskId(), task, client);
            }
            if (STATUS_RUNNING.equals(state.lastState())) {
                return checkRunning(sessionId, state, client);
            }
            return runTask(sessionId, task, state.contextId(), context, client);
        } finally {
            gate.release();
        }
    }

    private ToolCallResult runTask(String sessionId, String task, String contextId, ExecutionContext context, AgentHubClient client) {
        List<ForwardedAttachment> forwarded;
        try {
            forwarded = collectAttachments(sessionId, context);
        } catch (RuntimeException e) {
            return failure("Failed to deliver message attachments to helper '" + displayName + "': " + e.getMessage(), e);
        }
        var request = new AgentHubRunRequest();
        request.task = task;
        request.contextId = contextId;
        if (!forwarded.isEmpty()) {
            request.attachments = forwarded.stream().map(ForwardedAttachment::attachment).toList();
        }
        var result = mapResult(sessionId, client.run(helper.agentId, request));
        if (!forwarded.isEmpty()) {
            markDelivered(sessionId, forwarded);
            result.withStats("helper_attachments", forwarded.size());
        }
        return result;
    }

    /**
     * The current user message's attachments ride along with a delegation: they are uploaded through
     * the platform's blob flow and become hub attachments, so the server agent receives the file the
     * same way a chat message would deliver it. Each file goes to a helper at most once per thread —
     * follow-up calls and input_required replies reuse what the server session already holds.
     */
    private List<ForwardedAttachment> collectAttachments(String sessionId, ExecutionContext context) {
        var contents = context == null ? null : context.getAttachedContents();
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        var delivered = deliveredAttachments.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet());
        var forwarded = new ArrayList<ForwardedAttachment>();
        for (var content : contents) {
            if (content == null) {
                continue;
            }
            var key = attachmentKey(content);
            if (key == null || delivered.contains(key)) {
                continue;
            }
            forwarded.add(new ForwardedAttachment(key, forwardAttachment(key, content)));
        }
        return forwarded;
    }

    private String attachmentKey(AttachedContent content) {
        if (content.isBase64()) {
            return contentHash(content.data);
        }
        return content.url == null || content.url.isBlank() ? null : "url:" + content.url;
    }

    private String contentHash(String data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    private AgentHubAttachment forwardAttachment(String key, AttachedContent content) {
        if (!content.isBase64()) {
            return urlAttachment(content);
        }
        var cached = uploadedAttachments.get(key);
        if (cached != null) {
            return cached;
        }
        var uploader = uploaderSupplier == null ? null : uploaderSupplier.get();
        if (uploader == null) {
            throw new IllegalStateException("not signed in to core-ai-server, cannot deliver attachments");
        }
        var name = content.filename != null && !content.filename.isBlank() ? content.filename : defaultFileName(content.type);
        var contentType = content.mediaType != null && !content.mediaType.isBlank() ? content.mediaType : "application/octet-stream";
        var attachment = uploader.upload(Base64.getDecoder().decode(content.data), name, contentType);
        uploadedAttachments.put(key, attachment);
        return attachment;
    }

    /** A url-backed attachment is already fetchable by the platform, so it is handed over as-is. */
    private AgentHubAttachment urlAttachment(AttachedContent content) {
        var attachment = new AgentHubAttachment();
        attachment.url = content.url;
        attachment.type = typeName(content.type);
        attachment.fileName = content.filename;
        attachment.contentType = content.mediaType;
        return attachment;
    }

    private String typeName(AttachedContent.AttachedContentType type) {
        if (type == null) {
            return "file";
        }
        return switch (type) {
            case IMAGE -> "image";
            case PDF -> "pdf";
            case VIDEO -> "video";
            default -> throw new IllegalStateException("unexpected attachment type: " + type);
        };
    }

    private String defaultFileName(AttachedContent.AttachedContentType type) {
        if (type == null) {
            return "attachment.bin";
        }
        return switch (type) {
            case IMAGE -> "attachment.png";
            case PDF -> "attachment.pdf";
            case VIDEO -> "attachment.mp4";
            default -> throw new IllegalStateException("unexpected attachment type: " + type);
        };
    }

    private void markDelivered(String sessionId, List<ForwardedAttachment> forwarded) {
        var delivered = deliveredAttachments.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet());
        for (var item : forwarded) {
            delivered.add(item.key());
        }
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

    private record ForwardedAttachment(String key, AgentHubAttachment attachment) {
    }

    public static class Builder extends ToolCall.Builder<Builder, HelperAgentToolCall> {
        private HelperEntry helper;
        private Supplier<AgentHubClient> clientSupplier;
        private Supplier<LocalAttachmentUploader> uploaderSupplier;

        public Builder helper(HelperEntry helper) {
            this.helper = helper;
            return self();
        }

        public Builder clientSupplier(Supplier<AgentHubClient> clientSupplier) {
            this.clientSupplier = clientSupplier;
            return self();
        }

        public Builder uploaderSupplier(Supplier<LocalAttachmentUploader> uploaderSupplier) {
            this.uploaderSupplier = uploaderSupplier;
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
            sourceType("agent");
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
            tool.uploaderSupplier = uploaderSupplier;
            return tool;
        }
    }
}
