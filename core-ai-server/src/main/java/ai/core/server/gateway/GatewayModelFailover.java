package ai.core.server.gateway;

import ai.core.agent.CancellationException;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.providers.LLMStreamException;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.GatewayModelConfig;
import core.framework.http.HTTPClientException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Failover policy for gateway model attempts.
 * <p>
 * A failure may switch to the next model in the configured chain only when it is provider-level
 * (network error, timeout, 5xx/429/401/403) and the attempt has not delivered anything downstream —
 * retrying after a chunk reached the caller would duplicate output. A failing model is also kept
 * out of the way for a short cooldown, so an outage does not pay the upstream timeout on every call.
 */
final class GatewayModelFailover {
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(60);
    private static final Pattern STATUS_CODE = Pattern.compile("statusCode=(\\d{3})");
    private static final Pattern TRANSIENT_MESSAGE = Pattern.compile(
            "connection reset|connection refused|connect timed out|read timed out|timed out|timeout|"
                    + "unexpected end of stream|stream was reset|broken pipe|"
                    + "bad gateway|gateway timeout|service unavailable|temporarily unavailable|"
                    + "overloaded|rate limit|too many requests|超时|服务不可用|限流",
            Pattern.CASE_INSENSITIVE);

    static boolean retryable(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            // a cancelled run is user intent, never a provider failure
            if (cause instanceof CancellationException || cause instanceof InterruptedException) return false;
            if (cause instanceof LLMStreamException) return transientMessage(cause.getMessage());
            if (cause instanceof HTTPClientException httpClientException) {
                if ("INVALID_URL".equals(httpClientException.errorCode())) return false;
                var statusCode = statusCode(httpClientException.getMessage());
                if (statusCode != null) return failoverStatus(statusCode);
                // no status means the request never completed: connect/read failure or a broken stream
                return true;
            }
            if (cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException) return true;
            if (cause instanceof IOException) return true;
        }
        return transientMessage(error.getMessage());
    }

    // a fallback must accept everything the request carries, otherwise the retry fails for a second reason
    static boolean supportsRequest(GatewayModelConfig model, CompletionRequest request) {
        if (model == null) return true;
        if (Boolean.FALSE.equals(model.supportsVision) && hasVisualContent(request.messages)) return false;
        return !Boolean.FALSE.equals(model.supportsTools) || request.tools == null || request.tools.isEmpty();
    }

    private static boolean failoverStatus(int statusCode) {
        // 401/403 = this provider's credentials are broken; another model can still serve the run.
        // 400/404/413/422 are request/model specific, so switching models would only waste a call.
        return statusCode == 401 || statusCode == 403 || statusCode == 408 || statusCode == 425
                || statusCode == 429 || statusCode >= 500;
    }

    private static Integer statusCode(String message) {
        if (message == null) return null;
        var matcher = STATUS_CODE.matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static boolean transientMessage(String message) {
        return message != null && TRANSIENT_MESSAGE.matcher(message).find();
    }

    private static boolean hasVisualContent(List<Message> messages) {
        if (messages == null) return false;
        for (var message : messages) {
            if (message.content == null) continue;
            for (var content : message.content) {
                if (content != null && (content.type == Content.ContentType.IMAGE_URL || content.type == Content.ContentType.VIDEO)) {
                    return true;
                }
            }
        }
        return false;
    }

    private final Map<String, Long> unhealthyUntil = new ConcurrentHashMap<>();

    boolean isUnhealthy(String modelId) {
        if (modelId == null) return false;
        var until = unhealthyUntil.get(modelId);
        return until != null && until - System.nanoTime() > 0;
    }

    void recordFailure(String modelId) {
        if (modelId != null) unhealthyUntil.put(modelId, System.nanoTime() + FAILURE_COOLDOWN.toNanos());
    }

    void recordSuccess(String modelId) {
        if (modelId != null) unhealthyUntil.remove(modelId);
    }

    /**
     * Wraps the caller callback for one attempt and records whether the attempt already delivered
     * anything downstream, which makes a retry on another model unsafe.
     */
    static final class OutputTrackingCallback implements StreamingCallback {
        private final StreamingCallback delegate;
        // passthrough requests forward raw SSE frames to their caller; the agent path does not
        private final boolean rawDataVisible;
        private volatile boolean outputEmitted;

        OutputTrackingCallback(StreamingCallback delegate, boolean rawDataVisible) {
            this.delegate = delegate;
            this.rawDataVisible = rawDataVisible;
        }

        boolean outputEmitted() {
            return outputEmitted;
        }

        @Override
        public void onChunk(String chunk) {
            if (chunk != null && !chunk.isEmpty()) outputEmitted = true;
            delegate.onChunk(chunk);
        }

        @Override
        public void onReasoningChunk(String chunk) {
            if (chunk != null && !chunk.isEmpty()) outputEmitted = true;
            delegate.onReasoningChunk(chunk);
        }

        @Override
        public void onTool(List<FunctionCall> functionCalls) {
            if (functionCalls != null && !functionCalls.isEmpty()) outputEmitted = true;
            delegate.onTool(functionCalls);
        }

        @Override
        public void onToolComplete(List<FunctionCall> functionCalls) {
            outputEmitted = true;
            delegate.onToolComplete(functionCalls);
        }

        @Override
        public void onReasoningComplete(String reasoning) {
            if (reasoning != null && !reasoning.isEmpty()) outputEmitted = true;
            delegate.onReasoningComplete(reasoning);
        }

        // passthrough surfaces (the CLI proxy) forward every raw SSE frame to the client
        @Override
        public void onRawData(String sseData) {
            if (rawDataVisible && sseData != null && !sseData.isEmpty()) outputEmitted = true;
            delegate.onRawData(sseData);
        }

        @Override
        public void onComplete() {
            outputEmitted = true;
            delegate.onComplete();
        }

        @Override
        public void onError(Throwable error) {
            outputEmitted = true;
            delegate.onError(error);
        }

        @Override
        public void onOutput(String source, String callId, String chunk) {
            if (chunk != null && !chunk.isEmpty()) outputEmitted = true;
            delegate.onOutput(source, callId, chunk);
        }

        @Override
        public void setActiveConnection(AutoCloseable connection) {
            delegate.setActiveConnection(connection);
        }

        @Override
        public void cancelConnection() {
            delegate.cancelConnection();
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public void reset() {
            delegate.reset();
        }
    }

    /**
     * Captures the request fields an attempt may rewrite (model, endpoint, temperature, reasoning
     * mapping, response format, messages) so the next attempt starts from the caller's original request.
     */
    static final class RequestSnapshot {
        static RequestSnapshot capture(CompletionRequest request) {
            return new RequestSnapshot(request);
        }

        private static List<Message> copyMessages(List<Message> messages) {
            if (messages == null) return null;
            var copy = new ArrayList<Message>(messages.size());
            for (var message : messages) {
                copy.add(copyMessage(message));
            }
            return copy;
        }

        private static Message copyMessage(Message message) {
            if (message == null) return null;
            var copy = new Message();
            copy.role = message.role;
            copy.content = copyContent(message.content);
            copy.reasoningContent = message.reasoningContent;
            copy.name = message.name;
            copy.toolCallId = message.toolCallId;
            copy.toolCalls = message.toolCalls;
            return copy;
        }

        private static List<Content> copyContent(List<Content> content) {
            if (content == null) return null;
            var copy = new ArrayList<Content>(content.size());
            for (var part : content) {
                if (part == null) {
                    copy.add(null);
                    continue;
                }
                var partCopy = new Content();
                partCopy.type = part.type;
                partCopy.text = part.text;
                partCopy.imageUrl = part.imageUrl;
                partCopy.file = part.file;
                partCopy.video = part.video;
                copy.add(partCopy);
            }
            return copy;
        }

        private final String model;
        private final String endpoint;
        private final Double temperature;
        private final ReasoningEffort reasoningEffort;
        private final String reasoningEffortValue;
        private final ResponseFormat responseFormat;
        private final List<Message> messages;

        private RequestSnapshot(CompletionRequest request) {
            model = request.model;
            endpoint = request.getEndpoint();
            temperature = request.temperature;
            reasoningEffort = request.reasoningEffort;
            reasoningEffortValue = request.getReasoningEffortValue();
            responseFormat = request.responseFormat;
            messages = copyMessages(request.messages);
        }

        void restore(CompletionRequest request) {
            request.model = model;
            request.setEndpoint(endpoint);
            request.temperature = temperature;
            request.reasoningEffort = reasoningEffort;
            request.setReasoningEffortValue(reasoningEffortValue);
            request.responseFormat = responseFormat;
            // restore a fresh copy: preprocessing mutates messages in place
            request.messages = copyMessages(messages);
        }
    }
}
