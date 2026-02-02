package ai.core.llm.providers;

import ai.core.agent.streaming.DefaultStreamingCallback;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.Usage;
import ai.core.document.Embedding;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author stephen
 */
public class LiteLLMProvider extends LLMProvider {
    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_WAIT_TIME = Duration.ofSeconds(3);

    private final String url;
    private final String token;
    private final HTTPClient client;

    public LiteLLMProvider(LLMProviderConfig config, String url, String token) {
        super(config);
        this.url = url;
        this.token = token;
        this.client = HTTPClient.builder()
                .connectTimeout(config.getConnectTimeout())
                .timeout(config.getTimeout())
                .maxRetries(MAX_RETRIES)
                .retryWaitTime(RETRY_WAIT_TIME)
                .trustAll()
                .build();
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest dto) {
        return doCompletionStream(dto, new DefaultStreamingCallback());
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest dto, StreamingCallback callback) {
        return chatCompletionStream(dto, callback);
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
        var extraBody = request.getExtraBody() == null ? config.getRequestExtraBody() : request.getExtraBody();
        var req = new HTTPRequest(HTTPMethod.POST, url + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        req.headers.put("Accept", "text/event-stream");
        if (!Strings.isBlank(token)) {
            req.headers.put("Authorization", "Bearer " + token);
        }

        var bodyMap = (Map<String, Object>) JsonUtil.toMap(request);
        if (extraBody instanceof Map<?, ?> extraMap) {
            bodyMap.putAll((Map<String, Object>) extraMap);
        }
        req.body(JsonUtil.toJson(bodyMap).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        return executeSSERequest(req, callback);
    }

    private CompletionResponse executeSSERequest(HTTPRequest req, StreamingCallback callback) {
        var rsp = client.execute(req);

        if (rsp.statusCode != 200) {
            throw new RuntimeException(rsp.text());
        }

        // Process SSE stream
        CompletionResponse finalResponse = null;
        var lines = rsp.text().split("\n");
        for (var line : lines) {
            // Skip empty lines and lines that do not start with "data: "
            if (!line.startsWith("data: ")) continue;

            var data = line.substring(6).trim();
            if ("[DONE]".equals(data)) {
                break;
            }

            var chunk = JSON.fromJSON(CompletionResponse.class, data);
            if (chunk.choices == null || chunk.choices.isEmpty()) {
                // Skip chunks without choices
                continue;
            }

            var choice = chunk.choices.getFirst();
            if (choice.delta != null && choice.delta.content != null) {
                // Call the streaming callback with the content delta
                callback.onChunk(choice.delta.content);
            }
            if (choice.delta != null && choice.delta.reasoningContent != null) {
                // Call the streaming callback with the content delta
                callback.onReasoningChunk(choice.delta.reasoningContent);
            }

            // Initialize final response with first chunk
            if (finalResponse == null) {
                finalResponse = chunk;
                // Initialize message from delta
                initializeFinalChoiceMessage(finalResponse);
            } else {
                // Merge streaming chunks into final response
                mergeChunkIntoFinalResponse(finalResponse, chunk);
            }
        }

        callback.onComplete();
        if (!Objects.requireNonNull(finalResponse).choices.getFirst().message.reasoningContent.isEmpty()) {
            callback.onReasoningComplete(finalResponse.choices.getFirst().message.reasoningContent);
        }
        return finalResponse;
    }

    private void initializeFinalChoiceMessage(CompletionResponse finalResponse) {
        if (finalResponse.choices == null || finalResponse.choices.isEmpty()) {
            return;
        }

        var finalChoice = finalResponse.choices.getFirst();
        if (finalChoice.delta == null) {
            return;
        }

        finalChoice.message = new AssistantMessage();
        finalChoice.message.role = finalChoice.delta.role;
        finalChoice.message.content = finalChoice.delta.content != null ? finalChoice.delta.content : "";
        finalChoice.message.reasoningContent = finalChoice.delta.reasoningContent != null ? finalChoice.delta.reasoningContent : "";
        finalChoice.message.toolCalls = finalChoice.delta.toolCalls != null ? new ArrayList<>(finalChoice.delta.toolCalls) : new ArrayList<>();
    }

    private void mergeChunkIntoFinalResponse(CompletionResponse finalResponse, CompletionResponse chunk) {
        if (chunk.choices != null && !chunk.choices.isEmpty() && finalResponse.choices != null && !finalResponse.choices.isEmpty()) {
            var finalChoice = finalResponse.choices.getFirst();
            var chunkChoice = chunk.choices.getFirst();

            if (chunkChoice.delta != null) {
                copyDeltaToFinalChoice(finalChoice, chunkChoice);
            }

            // Update finish reason from chunk
            if (chunkChoice.finishReason != null) {
                finalChoice.finishReason = chunkChoice.finishReason;
            }
        }

        // Update usage from chunk
        if (chunk.usage != null) {
            finalResponse.usage = chunk.usage;
        }
    }

    private void copyDeltaToFinalChoice(Choice finalChoice, Choice chunkChoice) {
        // Ensure message exists
        if (finalChoice.message == null) {
            finalChoice.message = new AssistantMessage();
            finalChoice.message.content = "";
            finalChoice.message.reasoningContent = "";
            finalChoice.message.toolCalls = new ArrayList<>();
        }

        // Merge content into message
        if (chunkChoice.delta.content != null) {
            finalChoice.message.content += chunkChoice.delta.content;
        }
        if (chunkChoice.delta.reasoningContent != null) {
            finalChoice.message.reasoningContent += chunkChoice.delta.reasoningContent;
        }

        // Merge tool calls into message by index
        if (chunkChoice.delta.toolCalls != null) {
            copyToolCallsToFinalChoice(finalChoice, chunkChoice);
        }

        // Merge role if not set
        if (chunkChoice.delta.role != null && finalChoice.message.role == null) {
            finalChoice.message.role = chunkChoice.delta.role;
        }
    }

    private void copyToolCallsToFinalChoice(Choice finalChoice, Choice chunkChoice) {
        if (finalChoice.message.toolCalls == null) {
            finalChoice.message.toolCalls = new ArrayList<>();
        }

        for (var deltaToolCall : chunkChoice.delta.toolCalls) {
            if (deltaToolCall.index == null) {
                continue;
            }

            while (finalChoice.message.toolCalls.size() <= deltaToolCall.index) {
                finalChoice.message.toolCalls.add(null);
            }

            var existingToolCall = finalChoice.message.toolCalls.get(deltaToolCall.index);
            if (existingToolCall == null) {
                existingToolCall = new FunctionCall();
                existingToolCall.function = new FunctionCall.Function();
                existingToolCall.function.arguments = "";
                finalChoice.message.toolCalls.set(deltaToolCall.index, existingToolCall);
            }

            if (deltaToolCall.id != null) {
                existingToolCall.id = deltaToolCall.id;
            }
            if (deltaToolCall.type != null) {
                existingToolCall.type = deltaToolCall.type;
            }
            if (deltaToolCall.function != null) {
                if (deltaToolCall.function.name != null) {
                    existingToolCall.function.name = deltaToolCall.function.name;
                }
                if (deltaToolCall.function.arguments != null) {
                    existingToolCall.function.arguments += deltaToolCall.function.arguments;
                }
            }
        }
    }
}
