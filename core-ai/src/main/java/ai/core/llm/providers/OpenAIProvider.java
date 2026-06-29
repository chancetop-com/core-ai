package ai.core.llm.providers;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.utils.JsonUtil;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import core.framework.util.Strings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native OpenAI provider using the Responses API via the official OpenAI Java SDK.
 * Supports both api.openai.com and Azure OpenAI endpoints.
 */
public class OpenAIProvider extends LLMProvider {

    private volatile OpenAIClientImpl client;

    public OpenAIProvider(LLMProviderConfig config, String apiKey) {
        this(config, apiKey, null);
    }

    public OpenAIProvider(LLMProviderConfig config, String apiKey, String baseUrl) {
        super(config);
        this.client = buildClient(apiKey, baseUrl);
    }

    public void updateApiKey(String apiKey) {
        this.client = buildClient(apiKey, null);
    }

    private OpenAIClientImpl buildClient(String apiKey, String baseUrl) {
        var builder = ClientOptions.builder()
                .apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }
        if (config.getTimeout() != null) {
            builder.timeout(config.getTimeout());
        }
        return new OpenAIClientImpl(builder.build());
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        var params = buildRequestParams(request);
        var options = buildRequestOptions(request);
        var response = client.withOptions(o -> o.timeout(options.getTimeout().read()))
                .responses()
                .create(params);
        return convertResponse(response);
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        var params = buildRequestParams(request);
        var options = buildRequestOptions(request);
        var stream = client.withOptions(o -> o.timeout(options.getTimeout().read()))
                .responses()
                .createStreaming(params);
        return processStream(stream, callback);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        var params = EmbeddingCreateParams.builder()
                .model(config.getEmbeddingModel())
                .inputOfArrayOfStrings(request.query())
                .build();
        var response = client.embeddings().create(params);
        return convertEmbeddingResponse(request.query(), response);
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) {
        return null;
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        return null;
    }

    @Override
    public String name() {
        return "openai";
    }

    // ─── Request building ────────────────────────────────────────────

    private ResponseCreateParams buildRequestParams(CompletionRequest request) {
        var builder = ResponseCreateParams.builder();

        builder.model(request.model);

        if (request.temperature != null) {
            builder.temperature(request.temperature.doubleValue());
        }

        convertMessages(builder, request.messages);

        if (request.tools != null && !request.tools.isEmpty()) {
            for (var tool : request.tools) {
                builder.addTool(convertTool(tool));
            }
        }

        if (request.responseFormat != null) {
            convertResponseFormat(builder, request.responseFormat);
        }

        if (request.reasoningEffort != null) {
            builder.reasoning(Reasoning.builder()
                    .effort(convertReasoningEffort(request.reasoningEffort))
                    .build());
        }

        // Extra body — both per-request and per-model from config
        var extraBody = request.getExtraBody() != null
                ? request.getExtraBody()
                : config.resolveExtraBody(request.model);
        if (extraBody instanceof Map<?, ?> extraMap) {
            for (var entry : extraMap.entrySet()) {
                builder.putAdditionalBodyProperty(
                        String.valueOf(entry.getKey()),
                        JsonValue.from(entry.getValue()));
            }
        }

        return builder.build();
    }

    private RequestOptions buildRequestOptions(CompletionRequest request) {
        var timeout = request.getTimeoutSeconds() != null
                ? Duration.ofSeconds(request.getTimeoutSeconds())
                : config.getTimeout();
        return RequestOptions.builder()
                .timeout(timeout)
                .build();
    }

    // ─── Message conversion ──────────────────────────────────────────

    private void convertMessages(ResponseCreateParams.Builder builder, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        StringBuilder instructionsBuf = new StringBuilder();
        List<ResponseInputItem> inputItems = new ArrayList<>();

        for (var msg : messages) {
            switch (msg.role) {
                case SYSTEM -> {
                    var text = msg.getTextContent();
                    if (!Strings.isBlank(text)) {
                        if (instructionsBuf.length() > 0) instructionsBuf.append('\n');
                        instructionsBuf.append(text);
                    }
                }
                case USER -> {
                    if (msg.content != null) {
                        for (var c : msg.content) {
                            if (c.type == Content.ContentType.TEXT && !Strings.isBlank(c.text)) {
                                inputItems.add(buildUserMessage(c.text));
                            } else if (c.type == Content.ContentType.IMAGE_URL && c.imageUrl != null) {
                                inputItems.add(buildUserImageMessage(c.imageUrl.url));
                            }
                        }
                    }
                }
                case ASSISTANT -> {
                    inputItems.addAll(buildAssistantMessages(msg));
                }
                case TOOL -> {
                    if (!Strings.isBlank(msg.toolCallId) && !Strings.isBlank(msg.getTextContent())) {
                        inputItems.add(buildFunctionCallOutput(msg.toolCallId, msg.getTextContent()));
                    }
                }
            }
        }

        if (instructionsBuf.length() > 0) {
            builder.instructions(instructionsBuf.toString());
        }
        if (!inputItems.isEmpty()) {
            builder.inputOfResponse(inputItems);
        }
    }

    private ResponseInputItem buildUserMessage(String text) {
        return ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content(text)
                        .build());
    }

    private ResponseInputItem buildUserImageMessage(String imageUrl) {
        var imageContent = ResponseInputContent.ofInputImage(
                com.openai.models.responses.ResponseInputImage.builder()
                        .imageUrl(imageUrl)
                        .build());
        return ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder()
                        .role(ResponseInputItem.Message.Role.USER)
                        .addContent(imageContent)
                        .build());
    }

    private List<ResponseInputItem> buildAssistantMessages(Message msg) {
        var items = new ArrayList<ResponseInputItem>();

        // Assistant message content
        if (!Strings.isBlank(msg.getTextContent())) {
            items.add(ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.ASSISTANT)
                            .content(msg.getTextContent())
                            .build()));
        }

        // Tool calls made by the assistant
        if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
            for (var tc : msg.toolCalls) {
                items.add(ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .callId(tc.id)
                                .name(tc.function.name)
                                .arguments(tc.function.arguments)
                                .build()));
            }
        }

        return items;
    }

    private ResponseInputItem buildFunctionCallOutput(String toolCallId, String output) {
        return ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                        .callId(toolCallId)
                        .output(output)
                        .build());
    }

    // ─── Tool conversion ─────────────────────────────────────────────

    private FunctionTool convertTool(ai.core.llm.domain.Tool tool) {
        var func = tool.function;
        var paramsBuilder = FunctionTool.Parameters.builder();

        if (func.parameters != null) {
            var schemaMap = JsonUtil.toMap(JsonUtil.toJson(func.parameters));
            for (var entry : schemaMap.entrySet()) {
                paramsBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        } else {
            paramsBuilder.putAdditionalProperty("type", JsonValue.from("object"));
            paramsBuilder.putAdditionalProperty("properties", JsonValue.from(Map.of()));
        }

        return FunctionTool.builder()
                .name(func.name)
                .description(func.description)
                .parameters(paramsBuilder.build())
                .build();
    }

    // ─── Response format conversion ──────────────────────────────────

    private void convertResponseFormat(ResponseCreateParams.Builder builder, ResponseFormat format) {
        if (format.jsonSchema == null) return;

        var schemaDef = format.jsonSchema;
        var schemaBuilder = ResponseFormatTextJsonSchemaConfig.Schema.builder();

        if (schemaDef.schema instanceof Map<?, ?> schemaMap) {
            for (var entry : schemaMap.entrySet()) {
                schemaBuilder.putAdditionalProperty(String.valueOf(entry.getKey()), JsonValue.from(entry.getValue()));
            }
        } else {
            schemaBuilder.putAdditionalProperty("type", JsonValue.from("object"));
        }

        var jsonSchemaConfig = ResponseFormatTextJsonSchemaConfig.builder()
                .name(schemaDef.name)
                .strict(schemaDef.strict != null && schemaDef.strict)
                .schema(schemaBuilder.build())
                .build();

        var textConfig = ResponseTextConfig.builder()
                .format(jsonSchemaConfig)
                .build();

        builder.text(textConfig);
    }

    // ─── Reasoning conversion ────────────────────────────────────────

    private ReasoningEffort convertReasoningEffort(ai.core.llm.domain.ReasoningEffort effort) {
        return switch (effort) {
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
        };
    }

    // ─── Response conversion ─────────────────────────────────────────

    private CompletionResponse convertResponse(Response response) {
        var choice = new Choice();
        choice.index = 0;
        choice.message = new AssistantMessage();
        choice.message.role = RoleType.ASSISTANT;
        choice.message.content = "";
        choice.message.reasoningContent = "";
        choice.message.toolCalls = new ArrayList<>();

        boolean hasToolCalls = false;

        for (var item : response.output()) {
            if (item.isMessage()) {
                extractMessageContent(choice.message, item.asMessage());
            } else if (item.isFunctionCall()) {
                choice.message.toolCalls.add(convertFunctionToolCall(item.asFunctionCall()));
                hasToolCalls = true;
            } else if (item.isReasoning()) {
                choice.message.reasoningContent = extractReasoningText(item.asReasoning());
            }
        }

        if (hasToolCalls) {
            choice.finishReason = FinishReason.TOOL_CALLS;
        } else {
            choice.finishReason = FinishReason.STOP;
        }

        var usage = response.usage()
                .map(u -> new Usage(
                        (int) u.inputTokens(),
                        (int) u.outputTokens(),
                        (int) u.totalTokens()))
                .orElse(new Usage(0, 0, 0));

        return CompletionResponse.of(List.of(choice), usage);
    }

    private void extractMessageContent(AssistantMessage message, ResponseOutputMessage msg) {
        var sb = new StringBuilder();
        for (var content : msg.content()) {
            if (content.isOutputText()) {
                sb.append(content.asOutputText().text());
            }
        }
        message.content = sb.toString();
    }

    private FunctionCall convertFunctionToolCall(ResponseFunctionToolCall toolCall) {
        return FunctionCall.of(
                toolCall.callId(),
                "function",
                toolCall.name(),
                toolCall.arguments());
    }

    private String extractReasoningText(ResponseReasoningItem reasoning) {
        if (reasoning.summary() == null || reasoning.summary().isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (var summary : reasoning.summary()) {
            sb.append(summary.text());
        }
        return sb.toString();
    }

    // ─── Stream processing ──────────────────────────────────────────

    private CompletionResponse processStream(
            StreamResponse<ResponseStreamEvent> stream,
            StreamingCallback callback) {

        var finalChoice = new Choice();
        finalChoice.index = 0;
        finalChoice.message = new AssistantMessage();
        finalChoice.message.content = "";
        finalChoice.message.reasoningContent = "";
        finalChoice.message.toolCalls = new ArrayList<>();
        finalChoice.message.role = RoleType.ASSISTANT;

        CompletionResponse response = CompletionResponse.of(List.of(finalChoice), null);

        // Track pending function calls by itemId+outputIndex
        Map<String, FunctionCall> pendingToolCalls = new HashMap<>();

        try (stream; var eventStream = stream.stream()) {
            eventStream.takeWhile(e -> !callback.isCancelled()).forEach(event -> {
                if (event.isOutputTextDelta()) {
                    var delta = event.asOutputTextDelta().delta();
                    callback.onChunk(delta);
                    finalChoice.message.appendContent(delta);
                } else if (event.isReasoningTextDelta()) {
                    var delta = event.asReasoningTextDelta().delta();
                    callback.onReasoningChunk(delta);
                    finalChoice.message.appendReasoningContent(delta);
                } else if (event.isFunctionCallArgumentsDelta()) {
                    var funcDelta = event.asFunctionCallArgumentsDelta();
                    var key = funcDelta.itemId() + ":" + funcDelta.outputIndex();
                    var fc = pendingToolCalls.computeIfAbsent(key, k -> {
                        var newFc = new FunctionCall();
                        newFc.id = funcDelta.itemId();
                        newFc.type = "function";
                        newFc.function = new FunctionCall.Function();
                        newFc.function.arguments = "";
                        return newFc;
                    });
                    fc.function.appendArguments(funcDelta.delta());
                } else if (event.isOutputItemDone()) {
                    var item = event.asOutputItemDone().item();
                    if (item.isFunctionCall()) {
                        var funcCall = item.asFunctionCall();
                        var fc = new FunctionCall();
                        fc.id = funcCall.callId();
                        fc.type = "function";
                        fc.function = new FunctionCall.Function();
                        fc.function.name = funcCall.name();
                        fc.function.arguments = funcCall.arguments();
                        finalChoice.message.toolCalls.add(fc);
                        callback.onTool(List.of(fc));
                    }
                } else if (event.isCompleted()) {
                    var completedResponse = event.asCompleted().response();
                    completedResponse.usage().ifPresent(u ->
                            response.usage = new Usage(
                                    (int) u.inputTokens(),
                                    (int) u.outputTokens(),
                                    (int) u.totalTokens()));
                }
            });
        }

        finalChoice.message.finalizeStreamingFields();

        if (callback.isCancelled()) {
            if (!Strings.isBlank(finalChoice.message.content)) {
                finalChoice.message.content += "\n\n[interrupted]";
            }
            finalChoice.finishReason = FinishReason.STOP;
            return response;
        }

        if (!finalChoice.message.toolCalls.isEmpty()) {
            finalChoice.finishReason = FinishReason.TOOL_CALLS;
            finalChoice.message.toolCalls.removeIf(java.util.Objects::isNull);
            callback.onToolComplete(finalChoice.message.toolCalls);
        } else {
            finalChoice.finishReason = FinishReason.STOP;
        }

        if (!Strings.isBlank(finalChoice.message.reasoningContent)) {
            callback.onReasoningComplete(finalChoice.message.reasoningContent);
        }

        callback.onComplete();
        return response;
    }

    // ─── Embeddings conversion ───────────────────────────────────────

    private EmbeddingResponse convertEmbeddingResponse(
            List<String> queries,
            CreateEmbeddingResponse openaiResponse) {

        var embeddings = new ArrayList<EmbeddingResponse.EmbeddingData>();
        for (var data : openaiResponse.data()) {
            var vectors = data.embedding().stream()
                    .map(Number::doubleValue)
                    .toList();
            var embedding = new ai.core.document.Embedding(vectors);
            embeddings.add(EmbeddingResponse.EmbeddingData.of(
                    queries.get((int) data.index()),
                    embedding));
        }

        var usage = new Usage(
                (int) openaiResponse.usage().promptTokens(),
                0,
                (int) openaiResponse.usage().totalTokens());

        return EmbeddingResponse.of(embeddings, usage);
    }
}
