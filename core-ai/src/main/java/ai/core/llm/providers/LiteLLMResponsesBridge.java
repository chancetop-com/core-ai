package ai.core.llm.providers;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.util.Strings;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

final class LiteLLMResponsesBridge {
    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_WAIT_TIME = Duration.ofSeconds(3);

    static boolean isResponsesModel(String model) {
        return LiteLLMResponsesRequestMapper.isResponsesModel(model);
    }

    static Map<String, Object> toResponsesBody(CompletionRequest request) {
        return LiteLLMResponsesRequestMapper.toResponsesBody(request);
    }

    private final HTTPClient client;
    private final String url;
    private final String token;
    private final LLMProviderConfig config;

    LiteLLMResponsesBridge(HTTPClient client, String url, String token, LLMProviderConfig config) {
        this.client = client;
        this.url = url;
        this.token = token;
        this.config = config;
    }

    CompletionResponse completionStream(CompletionRequest request, StreamingCallback callback) {
        var extraBody = request.getExtraBody() != null ? request.getExtraBody() : config.resolveExtraBody(request.model);
        var req = new HTTPRequest(HTTPMethod.POST, url + "/responses");
        if (request.getTimeoutSeconds() != null) {
            req.timeout = Duration.ofSeconds(request.getTimeoutSeconds());
        }
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        if (!Strings.isBlank(token)) {
            req.headers.put("Authorization", "Bearer " + token);
        }

        var bodyMap = toResponsesBody(request);
        if (extraBody instanceof Map<?, ?> extraMap) {
            bodyMap.putAll(LiteLLMResponsesUtil.asStringObjectMap(extraMap));
        }
        req.body(JsonUtil.toJson(bodyMap).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        return executeSSERequest(req, callback);
    }

    private CompletionResponse executeSSERequest(HTTPRequest req, StreamingCallback callback) {
        CompletionResponse response = null;
        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (callback.isCancelled()) break;
            try {
                response = consumeSSEStream(req, callback);
                break;
            } catch (Exception e) {
                if (callback.isCancelled()) break;
                lastError = e;
                if (attempt < MAX_RETRIES && !retrySleep()) break;
            }
        }
        if (callback.isCancelled()) {
            if (hasPartialContent(response)) {
                var choice = response.choices.getFirst();
                choice.message.content += "\n\n[interrupted]";
                choice.finishReason = FinishReason.STOP;
            }
            return response;
        }
        if (response == null && lastError != null) {
            if (lastError instanceof RuntimeException runtimeException) throw runtimeException;
            throw new RuntimeException(lastError);
        }
        completeCallbacks(Objects.requireNonNull(response), callback);
        return response;
    }

    private CompletionResponse consumeSSEStream(HTTPRequest req, StreamingCallback callback) {
        var state = new LiteLLMResponsesStreamState();
        try (var eventSource = client.sse(req)) {
            callback.setActiveConnection(eventSource);
            for (var event : eventSource) {
                if (callback.isCancelled()) break;
                for (var payload : LiteLLMResponsesStreamState.responsePayloads(event.data())) {
                    state.accept(JsonUtil.fromJson(Map.class, payload), callback);
                }
            }
        } finally {
            state.finalizeResponse();
        }
        return state.response();
    }

    private void completeCallbacks(CompletionResponse response, StreamingCallback callback) {
        var message = response.choices.getFirst().message;
        if (!Strings.isBlank(message.reasoningContent)) {
            callback.onReasoningComplete(message.reasoningContent);
        }
        if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
            message.toolCalls.removeIf(Objects::isNull);
            callback.onToolComplete(message.toolCalls);
        }
        callback.onComplete();
    }

    private boolean retrySleep() {
        try {
            Thread.sleep(RETRY_WAIT_TIME.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean hasPartialContent(CompletionResponse response) {
        if (response == null || response.choices == null || response.choices.isEmpty()) return false;
        var message = response.choices.getFirst().message;
        return message != null && (!Strings.isBlank(message.content) || !Strings.isBlank(message.reasoningContent));
    }
}
