package ai.core.llm.providers;

import ai.core.agent.CancelReason;
import ai.core.agent.CancellationException;
import ai.core.llm.streaming.DefaultStreamingCallback;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.document.Embedding;
import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.Usage;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.io.JsonEOFException;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.util.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class LiteLLMProvider extends LLMProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiteLLMProvider.class);

    /**
     * Whether this model name selects the /responses transport instead of /chat/completions.
     */
    public static boolean isResponsesModel(String model) {
        return LiteLLMResponsesBridge.isResponsesModel(model);
    }

    private volatile String url;
    private volatile String token;
    private volatile String authHeaderName = "Authorization";
    private volatile String authHeaderValuePrefix = "Bearer ";
    private final HTTPClient client;

    public LiteLLMProvider(LLMProviderConfig config, String url, String token) {
        super(config);
        this.url = stripTrailingSlashes(url);
        this.token = token;
        this.client = new PatchedHTTPClientBuilder()
                .connectTimeout(config.getConnectTimeout())
                .timeout(config.getTimeout())
                .trustAll()
                .build();
    }

    public LiteLLMProvider(LLMProviderConfig config, String url, String token, String authHeaderName, String authHeaderValuePrefix) {
        this(config, url, token);
        this.authHeaderName = authHeaderName;
        this.authHeaderValuePrefix = authHeaderValuePrefix;
    }

    /**
     * Update the provider's endpoint and auth token at runtime, e.g. when the
     * active login session changes via {@code RuntimeAuthConfig}.
     */
    public void updateCredentials(String url, String token) {
        this.url = stripTrailingSlashes(url);
        this.token = token;
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest dto) {
        return doCompletionStream(dto, new DefaultStreamingCallback());
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest dto, StreamingCallback callback) {
        if (LiteLLMResponsesBridge.isResponsesRequest(dto)) {
            return new LiteLLMResponsesBridge(client, url, token, config).completionStream(dto, callback);
        }
        return chatCompletionStream(dto, callback);
    }

    /**
     * Executes the completion transport directly, skipping the {@link LLMProvider} template
     * (preprocess/tracing/postprocess). For delegating providers that already ran the template,
     * e.g. the server gateway bridge; regular callers should use completionStream instead.
     */
    public CompletionResponse delegateCompletionStream(CompletionRequest dto, StreamingCallback callback) {
        return doCompletionStream(dto, callback);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest dto) {
        var req = new HTTPRequest(HTTPMethod.POST, url + "/embeddings");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        if (!Strings.isBlank(token)) {
            req.headers.put("Authorization", "Bearer " + token);
        }

        var bodyMap = Map.of(
                "model", config.getEmbeddingModel(),
                "input", dto.query()
        );
        req.body(JsonUtil.toJson(bodyMap).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        var rsp = client.execute(req);
        if (rsp.statusCode != 200) {
            throw new RuntimeException("Embedding request failed: " + rsp.text());
        }

        return parseEmbeddingResponse(dto.query(), rsp.text());
    }

    @SuppressWarnings("unchecked")
    private EmbeddingResponse parseEmbeddingResponse(List<String> queries, String responseText) {
        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, responseText);
        var dataList = (List<Map<String, Object>>) responseMap.get("data");
        var usageMap = (Map<String, Object>) responseMap.get("usage");

        var embeddings = new ArrayList<EmbeddingResponse.EmbeddingData>();
        for (var data : dataList) {
            int index = ((Number) data.get("index")).intValue();
            var embeddingList = (List<Number>) data.get("embedding");
            var vectors = embeddingList.stream()
                    .map(Number::doubleValue)
                    .toList();
            var embedding = new Embedding(vectors);
            embeddings.add(EmbeddingResponse.EmbeddingData.of(queries.get(index), embedding));
        }

        var usage = new Usage(
                ((Number) usageMap.get("prompt_tokens")).intValue(),
                0,
                ((Number) usageMap.get("total_tokens")).intValue()
        );

        return EmbeddingResponse.of(embeddings, usage);
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) {
        return null;
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest dto) {
        return null;
    }

    @Override
    public String name() {
        return "litellm";
    }

    @SuppressWarnings("unchecked")
    public CompletionResponse chatCompletionStream(CompletionRequest request, StreamingCallback callback) {
        var extraBody = request.getExtraBody() != null ? request.getExtraBody() : config.resolveExtraBody(request.model);
        var reqUrl = url.contains("/chat/completions") ? url : url + "/chat/completions";
        var req = new HTTPRequest(HTTPMethod.POST, reqUrl);
        if (request.getTimeoutSeconds() != null) {
            req.timeout = Duration.ofSeconds(request.getTimeoutSeconds());
        }
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        if (!Strings.isBlank(token)) {
            req.headers.put(authHeaderName, authHeaderValuePrefix + token);
        }
        LiteLLMResponsesBridge.applyGatewayHeaders(req, request);

        var bodyMap = (Map<String, Object>) JsonUtil.toMap(request);
        if (request.getReasoningEffortValue() != null) {
            bodyMap.put("reasoning_effort", request.getReasoningEffortValue());
        }
        if (extraBody instanceof Map<?, ?> extraMap) {
            bodyMap.putAll((Map<String, Object>) extraMap);
        }
        req.body(JsonUtil.toJson(bodyMap).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        return executeSSERequest(req, callback, request.isPassthrough());
    }

    private CompletionResponse executeSSERequest(HTTPRequest req, StreamingCallback callback, boolean rawDataVisible) {
        var tracker = new LLMRetrySupport.OutputTrackingCallback(callback, rawDataVisible);
        boolean serverProxy = LLMRetrySupport.isServerProxyEndpoint(req.uri);
        CompletionResponse response = null;
        RuntimeException lastError = null;
        long firstFailureNanos = 0;
        int failures = 0;
        while (true) {
            if (callback.isCancelled()) break;
            try {
                response = consumeSSEStream(req, tracker);
                break;
            } catch (LLMStreamException e) {
                // an in-stream error event is a definitive upstream rejection; retrying cannot help
                throw e;
            } catch (RuntimeException e) {
                if (callback.isCancelled()) break;
                lastError = e;
                if (tracker.outputEmitted()) throw LLMRetrySupport.interruptedStream(req.uri, serverProxy, e);
                if (!LLMRetrySupport.retryable(e)) throw e;
                failures++;
                if (firstFailureNanos == 0) firstFailureNanos = System.nanoTime();
                if (!waitBeforeRetry(req.uri, serverProxy, failures, firstFailureNanos, tracker, e)) break;
            }
        }
        if (callback.isCancelled()) return cancelled(response, callback);
        if (response == null) {
            throw LLMRetrySupport.unavailable(req.uri, serverProxy, failures, firstFailureNanos, lastError);
        }
        completeCallbacks(response, callback);
        return response;
    }

    private boolean waitBeforeRetry(String uri, boolean serverProxy, int failures, long firstFailureNanos,
                                    StreamingCallback callback, RuntimeException error) {
        long delayMillis;
        if (serverProxy) {
            double jitter = ThreadLocalRandom.current().nextDouble(-LLMRetrySupport.SERVER_RETRY_JITTER, LLMRetrySupport.SERVER_RETRY_JITTER);
            delayMillis = LLMRetrySupport.retryDelayMillis(failures, jitter);
            long elapsedNanos = System.nanoTime() - firstFailureNanos;
            if (elapsedNanos + TimeUnit.MILLISECONDS.toNanos(delayMillis) > config.getServerRetryDuration().toNanos()) {
                LOGGER.warn("core-ai-server still unavailable after retries, endpoint={}, failures={}, elapsed={}ms, error={}",
                        uri, failures, elapsedNanos / 1_000_000L, LLMRetrySupport.errorMessage(error));
                return false;
            }
        } else {
            if (failures > LLMRetrySupport.MAX_RETRIES) return false;
            delayMillis = LLMRetrySupport.RETRY_WAIT_MILLIS;
        }
        LOGGER.warn("llm request failed, retrying, endpoint={}, failures={}, delay={}ms, error={}",
                uri, failures, delayMillis, LLMRetrySupport.errorMessage(error));
        return LLMRetrySupport.sleep(delayMillis, callback);
    }

    private CompletionResponse cancelled(CompletionResponse response, StreamingCallback callback) {
        if (!hasPartialContent(response)) {
            // cancelled before any content arrived — signal cancellation instead of a null response
            throw new CancellationException(CancelReason.USER_CANCELLED);
        }
        var choice = response.choices.getFirst();
        choice.message.content = (choice.message.content == null ? "" : choice.message.content) + "\n\n[interrupted]";
        choice.finishReason = FinishReason.STOP;
        // flush partial reasoning to listeners (e.g. server chat history) before the
        // turn unwinds; onComplete drains the async wrapper so the buffer write lands
        // before TurnCompleteEvent is dispatched
        if (!Strings.isBlank(choice.message.reasoningContent)) {
            callback.onReasoningComplete(choice.message.reasoningContent);
        }
        callback.onComplete();
        return response;
    }

    private void completeCallbacks(CompletionResponse response, StreamingCallback callback) {
        var message = response.choices.getFirst().message;
        if (message != null) {
            if (!Strings.isBlank(message.reasoningContent)) {
                callback.onReasoningComplete(message.reasoningContent);
            }
            if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
                message.toolCalls.removeIf(Objects::isNull);
                callback.onToolComplete(message.toolCalls);
            }
        }
        callback.onComplete();
    }

    private CompletionResponse consumeSSEStream(HTTPRequest req, StreamingCallback callback) {
        CompletionResponse response = null;
        boolean done = false;
        try (var eventSource = client.sse(req)) {
            callback.setActiveConnection(eventSource);

            for (var event : eventSource) {
                if (callback.isCancelled()) break;
                var data = event.data();
                callback.onRawData(data);
                if ("[DONE]".equals(data)) {
                    done = true;
                    break;
                }

                var chunk = parseChunk(req.uri, data);
                if (chunk.usage != null && response != null) {
                    response.usage = chunk.usage;
                }
                if (chunk.choices == null || chunk.choices.isEmpty()) {
                    continue;
                }

                var choice = chunk.choices.getFirst();
                if (Objects.nonNull(choice.delta) && StringUtils.isNotEmpty(choice.delta.reasoningContent)) {
                    callback.onReasoningChunk(choice.delta.reasoningContent);
                }
                if (Objects.nonNull(choice.delta) && StringUtils.isNotEmpty(choice.delta.content)) {
                    callback.onChunk(choice.delta.content);
                }
                if (response == null) {
                    response = chunk;
                    LiteLLMChunkMerger.initializeFinalChoiceMessage(response);
                }
                LiteLLMChunkMerger.mergeChunkIntoFinalResponse(response, chunk, callback);
            }
            if (callback.isCancelled()) return response;
            LiteLLMResponsesBridge.normalizeFinishReason(response);
            requireCompleteStream(req.uri, response, done);
            return response;
        } finally {
            // Sync builders to String fields so partial response is usable on error/cancel
            if (response != null && response.choices != null && !response.choices.isEmpty()) {
                var finalChoice = response.choices.getFirst();
                if (finalChoice.message != null) {
                    finalChoice.message.finalizeStreamingFields();
                }
            }
        }
    }

    private CompletionResponse parseChunk(String uri, String data) {
        try {
            return LiteLLMCompletionChunkParser.parse(data);
        } catch (UncheckedIOException e) {
            // core-ng EventSource hands out the last partial line as a regular event when the
            // connection drops mid-frame; report that as an interrupted transport, not as a
            // malformed model reply, so the retry policy and the caller see the real cause
            if (e.getCause() instanceof JsonEOFException) {
                throw LLMRetrySupport.incompleteStream(uri, LLMRetrySupport.isServerProxyEndpoint(uri), "mid-event", e);
            }
            throw e;
        }
    }

    private void requireCompleteStream(String uri, CompletionResponse response, boolean done) {
        if (response == null) {
            throw LLMRetrySupport.incompleteStream(uri, LLMRetrySupport.isServerProxyEndpoint(uri), "before sending any data");
        }
        // a stream without [DONE] and without a finish reason was cut by a proxy restart mid-reply;
        // accepting it would silently truncate the turn
        if (!done && response.choices.getFirst().finishReason == null) {
            throw LLMRetrySupport.incompleteStream(uri, LLMRetrySupport.isServerProxyEndpoint(uri), "before the reply finished");
        }
    }

    private boolean hasPartialContent(CompletionResponse response) {
        return response != null && response.choices != null && !response.choices.isEmpty()
                && response.choices.getFirst().message != null;
    }

    private String stripTrailingSlashes(String url) {
        if (url == null) return url;
        return url.replaceAll("/+$", "");
    }
}
