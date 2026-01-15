package ai.core.llm.providers;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
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
import java.util.Objects;

/**
 * @author stephen
 */
public class LiteLLMProvider extends LLMProvider {
    private final String url;
    private final String token;

    public LiteLLMProvider(LLMProviderConfig config, String url, String token) {
        super(config);
        this.url = url;
        this.token = token;
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest dto) {
        return chatCompletion(dto);
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest dto, StreamingCallback callback) {
        return chatCompletionStream(dto, callback);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest dto) {
        return null;
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

    public CompletionResponse chatCompletionStream(CompletionRequest request, StreamingCallback callback) {
        var client = HTTPClient.builder().timeout(Duration.ofSeconds(300)).maxRetries(3).retryWaitTime(Duration.ofSeconds(3)).trustAll().build();
        var req = new HTTPRequest(HTTPMethod.POST, url + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        req.headers.put("Accept", "text/event-stream");
        var body = JsonUtil.toJson(request).getBytes(StandardCharsets.UTF_8);
        req.body(body, ContentType.APPLICATION_JSON);
        if (!Strings.isBlank(token)) {
            req.headers.put("Authorization", "Bearer " + token);
        }

        return executeSSERequest(client, req, callback);
    }

    private CompletionResponse executeSSERequest(HTTPClient client, HTTPRequest req, StreamingCallback callback) {
        // Execute the request
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

        finalChoice.message = new Message();
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
            finalChoice.message = new Message();
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

    public CompletionResponse chatCompletion(CompletionRequest request) {
        var client = HTTPClient.builder().connectTimeout(this.config.getConnectTimeout()).timeout(this.config.getTimeout()).trustAll().build();
        var req = new HTTPRequest(HTTPMethod.POST, url + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        var body = JsonUtil.toJson(request).getBytes(StandardCharsets.UTF_8);
        req.body(body, ContentType.APPLICATION_JSON);
        if (!Strings.isBlank(token)) {
            req.headers.put("Authorization", "Bearer " + token);
        }
        var rsp = client.execute(req);
        if (rsp.statusCode != 200) {
            throw new RuntimeException(rsp.text());
        }
        var rst = JSON.fromJSON(CompletionResponse.class, rsp.text());
        rst.choices.forEach(v -> {
            if (v.message.content == null) {
                v.message.content = "";
            }
        });
        return rst;
    }
}
