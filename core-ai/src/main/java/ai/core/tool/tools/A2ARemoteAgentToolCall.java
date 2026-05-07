package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.a2a.A2AInvocationResult;
import ai.core.a2a.A2AOutputExtractor;
import ai.core.a2a.A2ARemoteAgentDescriptor;
import ai.core.a2a.A2AStreamEvent;
import ai.core.a2a.InMemoryRemoteAgentContextStore;
import ai.core.a2a.RemoteAgentClient;
import ai.core.a2a.RemoteAgentContext;
import ai.core.a2a.RemoteAgentContextStore;
import ai.core.agent.ExecutionContext;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.SendMessageConfiguration;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.TaskState;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Exposes an A2A-compatible remote agent as a normal Core-AI tool.
 *
 * @author xander
 */
public class A2ARemoteAgentToolCall extends ToolCall {
    private static final String QUERY_PARAM = "query";
    private static final String LOCAL_SESSION_FALLBACK = "local";

    public static Builder builder() {
        return new Builder();
    }

    private A2ARemoteAgentDescriptor descriptor;
    private Supplier<? extends RemoteAgentClient> clientFactory;
    private RemoteAgentContextStore contextStore;
    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        try {
            var query = extractQuery(arguments);
            var validation = validateQuery(query);
            if (validation != null) return validation.withDuration(System.currentTimeMillis() - startTime);
            var result = executeRemote(query, context);
            var duration = System.currentTimeMillis() - startTime;
            return result
                    .withDuration(duration)
                    .withStats("remote_duration_ms", duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolCallResult.failed("Remote agent '" + getName() + "' execution interrupted", e)
                    .withToolName(getName())
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Remote agent '" + getName() + "' execution error: " + e.getMessage(), e)
                    .withToolName(getName())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("A2A_REMOTE_AGENT_TOOL_FAILED", "A2ARemoteAgentToolCall requires ExecutionContext");
    }

    private ToolCallResult executeRemote(String query, ExecutionContext context) throws InterruptedException {
        var localSessionId = localSessionId(context);
        var remoteContext = loadRemoteContext(localSessionId);
        return executeRemoteWithGate(query, localSessionId, remoteContext);
    }

    private ToolCallResult executeRemoteWithGate(String query, String localSessionId,
                                                 RemoteAgentContext remoteContext) throws InterruptedException {
        var gateKey = gateKey(localSessionId, remoteContext);
        var gate = gates.computeIfAbsent(gateKey, ignored -> new Semaphore(1));
        gate.acquire();
        var releaseGate = true;
        try {
            var currentContext = loadRemoteContext(localSessionId);
            var currentGateKey = gateKey(localSessionId, currentContext);
            if (!currentGateKey.equals(gateKey)) {
                gate.release();
                releaseGate = false;
                return executeRemoteWithGate(query, localSessionId, currentContext);
            }
            var output = invokeRemote(query, currentContext);
            saveRemoteContext(localSessionId, output);
            return toToolResult(output);
        } catch (RuntimeException e) {
            if (isNotFound(e)) contextStore.delete(localSessionId, descriptor.id);
            throw e;
        } finally {
            if (releaseGate) gate.release();
        }
    }

    private String extractQuery(String arguments) {
        var args = parseArguments(arguments);
        return getStringValue(args, QUERY_PARAM);
    }

    private ToolCallResult validateQuery(String query) {
        if (query == null || query.isBlank()) {
            return ToolCallResult.failed("Parameter 'query' is required for remote agent " + getName())
                    .withToolName(getName());
        }
        if (query.length() > descriptor.maxInputChars) {
            return ToolCallResult.failed("Remote agent input is too large. Summarize it under "
                    + descriptor.maxInputChars + " characters and retry.")
                    .withToolName(getName())
                    .withStats("remote_agent_id", descriptor.id);
        }
        return null;
    }

    private A2AOutputExtractor.Output invokeRemote(String query, RemoteAgentContext remoteContext) {
        var request = buildRequest(query, remoteContext);
        var client = clientFactory.get();
        if (client == null) {
            throw new IllegalStateException("remote agent client is not available: " + descriptor.id);
        }
        if (descriptor.invocationMode == A2ARemoteAgentDescriptor.InvocationMode.SEND_SYNC) {
            A2AInvocationResult result = client.send(request);
            return new A2AOutputExtractor(descriptor.maxOutputChars).fromInvocation(result);
        }
        return invokeStream(client, request);
    }

    private A2AOutputExtractor.Output invokeStream(RemoteAgentClient client, SendMessageRequest request) {
        var subscriber = new CollectingSubscriber();
        client.stream(request).subscribe(subscriber);
        if (!subscriber.await(descriptor.timeout.toMillis())) {
            subscriber.cancel();
            throw new IllegalStateException("remote agent call timed out after " + descriptor.timeout);
        }
        if (subscriber.error != null) {
            throw new IllegalStateException("remote agent stream failed: " + subscriber.error.getMessage(), subscriber.error);
        }
        var output = new A2AOutputExtractor(descriptor.maxOutputChars).fromStreamEvents(subscriber.events);
        if (!isTerminal(output.state)) {
            throw new IllegalStateException("remote agent stream ended before terminal state: "
                    + (output.state != null ? output.state : "UNKNOWN"));
        }
        return output;
    }

    private SendMessageRequest buildRequest(String query, RemoteAgentContext remoteContext) {
        var message = Message.user(query);
        message.messageId = "m-" + UUID.randomUUID();
        if (shouldUseContext(remoteContext)) {
            message.contextId = remoteContext.contextId;
        }
        var request = new SendMessageRequest();
        request.message = message;
        request.configuration = configuration();
        return request;
    }

    private SendMessageConfiguration configuration() {
        var configuration = new SendMessageConfiguration();
        configuration.acceptedOutputModes = List.of("text/plain", "application/json");
        configuration.returnImmediately = false;
        return configuration;
    }

    private boolean shouldUseContext(RemoteAgentContext remoteContext) {
        return descriptor.contextPolicy == A2ARemoteAgentDescriptor.ContextPolicy.SESSION
                && remoteContext != null
                && remoteContext.hasContextId();
    }

    private RemoteAgentContext loadRemoteContext(String localSessionId) {
        if (descriptor.contextPolicy == A2ARemoteAgentDescriptor.ContextPolicy.NONE) return null;
        return contextStore.get(localSessionId, descriptor.id).orElse(null);
    }

    private void saveRemoteContext(String localSessionId, A2AOutputExtractor.Output output) {
        if (descriptor.contextPolicy == A2ARemoteAgentDescriptor.ContextPolicy.NONE || output.contextId == null) return;
        var remoteContext = new RemoteAgentContext();
        remoteContext.localSessionId = localSessionId;
        remoteContext.remoteAgentId = descriptor.id;
        remoteContext.contextId = output.contextId;
        remoteContext.lastTaskId = output.taskId;
        remoteContext.lastState = output.state;
        remoteContext.updatedAt = Instant.now();
        contextStore.save(remoteContext);
    }

    private ToolCallResult toToolResult(A2AOutputExtractor.Output output) {
        if (output.error != null && !output.error.isBlank()) return failed(output.error, output);
        if (output.state == TaskState.INPUT_REQUIRED) return inputRequired(output);
        if (output.state == TaskState.AUTH_REQUIRED) return failed("Remote agent requires authentication: " + statusText(output), output);
        if (output.state == TaskState.FAILED || output.state == TaskState.REJECTED) return failed("Remote agent failed: " + statusText(output), output);
        if (output.state == TaskState.CANCELED) return failed("Remote agent task was canceled: " + statusText(output), output);
        if (output.state != null && !isTerminal(output.state)) return failed("Remote agent did not reach terminal state: " + output.state, output);
        return withStats(ToolCallResult.completed(output.text), output);
    }

    private ToolCallResult inputRequired(A2AOutputExtractor.Output output) {
        var message = statusText(output);
        return withStats(ToolCallResult.completed("Remote agent requires input: " + message
                + "\nAsk the user and call this remote agent again with the answer."), output);
    }

    private ToolCallResult failed(String message, A2AOutputExtractor.Output output) {
        return withStats(ToolCallResult.failed(message), output);
    }

    private String statusText(A2AOutputExtractor.Output output) {
        if (output.statusText != null && !output.statusText.isBlank()) return output.statusText;
        if (output.text != null && !output.text.isBlank()) return output.text;
        return "(no details)";
    }

    private ToolCallResult withStats(ToolCallResult result, A2AOutputExtractor.Output output) {
        return result.withToolName(getName())
                .withStats("remote_agent_id", descriptor.id)
                .withStats("remote_task_id", output.taskId)
                .withStats("remote_context_id", output.contextId)
                .withStats("remote_state", output.state != null ? output.state.name() : null)
                .withStats("remote_output_truncated", output.truncated);
    }

    private String localSessionId(ExecutionContext context) {
        if (context != null && context.getSessionId() != null && !context.getSessionId().isBlank()) {
            return context.getSessionId();
        }
        return LOCAL_SESSION_FALLBACK;
    }

    private String gateKey(String localSessionId, RemoteAgentContext remoteContext) {
        if (remoteContext != null && remoteContext.hasContextId()) {
            return localSessionId + ":" + descriptor.id + ":" + remoteContext.contextId;
        }
        return localSessionId + ":" + descriptor.id + ":new";
    }

    private boolean isNotFound(RuntimeException e) {
        var message = e.getMessage();
        return message != null && message.contains("statusCode=404");
    }

    private boolean isTerminal(TaskState state) {
        return state == TaskState.COMPLETED
                || state == TaskState.FAILED
                || state == TaskState.CANCELED
                || state == TaskState.REJECTED
                || state == TaskState.INPUT_REQUIRED
                || state == TaskState.AUTH_REQUIRED;
    }

    public static class Builder extends ToolCall.Builder<Builder, A2ARemoteAgentToolCall> {
        private A2ARemoteAgentDescriptor descriptor;
        private Supplier<? extends RemoteAgentClient> clientFactory;
        private RemoteAgentContextStore contextStore;

        public Builder descriptor(A2ARemoteAgentDescriptor descriptor) {
            this.descriptor = descriptor;
            return this;
        }

        public Builder client(RemoteAgentClient client) {
            this.clientFactory = () -> client;
            return this;
        }

        public Builder clientFactory(Supplier<? extends RemoteAgentClient> clientFactory) {
            this.clientFactory = clientFactory;
            return this;
        }

        public Builder contextStore(RemoteAgentContextStore contextStore) {
            this.contextStore = contextStore;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public A2ARemoteAgentToolCall build() {
            if (descriptor == null) throw new IllegalArgumentException("descriptor is required");
            if (clientFactory == null) throw new IllegalArgumentException("clientFactory is required");
            name(descriptor.toolName);
            description(descriptor.toolDescription);
            timeoutMs(descriptor.timeout.toMillis());
            parameters(List.of(ToolCallParameter.builder()
                    .name(QUERY_PARAM)
                    .description("The query or instruction to send to the remote agent.")
                    .required(true)
                    .build()));
            var toolCall = new A2ARemoteAgentToolCall();
            super.build(toolCall);
            toolCall.descriptor = descriptor;
            toolCall.clientFactory = clientFactory;
            toolCall.contextStore = contextStore != null ? contextStore : new InMemoryRemoteAgentContextStore();
            return toolCall;
        }
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final List<A2AStreamEvent> events = new ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);
        private Flow.Subscription subscription;
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        boolean await(long timeoutMs) {
            try {
                return completed.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = e;
                cancel();
                return true;
            }
        }

        void cancel() {
            if (subscription != null) subscription.cancel();
        }
    }
}
