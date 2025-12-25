package ai.core.llm.providers;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.Usage;
import ai.core.utils.JsonUtil;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
//todo use openai sdk
public class LiteLLMProvider extends LLMProvider {
    private final Logger logger = LoggerFactory.getLogger(LiteLLMProvider.class);
    private final OpenAIClient client;

    public LiteLLMProvider(LLMProviderConfig config, String url, String token) {
        super(config);
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(token)
                .baseUrl(url)
                .timeout(config.getTimeout())
                .build();
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest dto) {
        throw new IllegalArgumentException("Not implemented");
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

        var createParams = toChatCompletionCreateParams(request);
        var usage = new Usage(0, 0, 0);
        var contentBuilder = new StringBuilder();
        var completionsFunctionToolCalls = new ArrayList<ChatCompletionChunk.Choice.Delta.ToolCall>();
        try (StreamResponse<ChatCompletionChunk> streamResponse =
                     client.chat().completions().createStreaming(createParams)) {

            streamResponse
                    .stream()
                    .peek(completion -> assignUsage(usage, completion))
                    .flatMap(completion -> completion.choices().stream())
                    .peek(choice -> logger.debug("Completion choice: {}", JsonUtil.toJson(choice.delta())))
                    .peek(choice -> choice.delta().content().ifPresent(callback::onChunk))
                    .peek(choice -> choice.delta().content().ifPresent(contentBuilder::append))
                    .forEach(choice -> choice.delta().toolCalls().ifPresent(completionsFunctionToolCalls::addAll));
            callback.onComplete();
            var message = new Message();
            message.role = RoleType.ASSISTANT;
            message.content = contentBuilder.toString();
            message.toolCalls = completionsFunctionToolCalls.isEmpty() ? null : toFc(completionsFunctionToolCalls);
            message.name = request.getName();

            var finishReason = completionsFunctionToolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
            return CompletionResponse.of(List.of(Choice.of(finishReason, message)), usage);
        } catch (Exception e) {
            logger.error("Error in streaming completion: {}", e.getMessage(), e);
            callback.onError(e);
            throw new RuntimeException("Streaming failed", e);
        }
    }

    private List<FunctionCall> toFc(List<ChatCompletionChunk.Choice.Delta.ToolCall> toolCalls) {

        var fcs = toolCalls
                .stream()
                .map(t -> Tuples.of(t.id(), t.function()))
                .filter(tup -> tup.getT1().isPresent() || tup.getT2().isPresent())
                .map(tup -> FunctionCall.of(tup.getT1().orElse(null), "function", tup.getT2().flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::name).orElse(null), tup.getT2().flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::arguments).orElse(null)))
                .toList();
        List<FunctionCall> result = new ArrayList<>();
        FunctionCall current = null;
        for (FunctionCall fc : fcs) {
            if (Objects.nonNull(fc.id)) {
                current = FunctionCall.of(fc.id, fc.type, fc.function.name, fc.function.arguments);
                result.add(current);
                continue;
            }
            if (Objects.nonNull(current) && Objects.nonNull(fc.function.name)) {
                current.function.name += fc.function.name;
            }
            if (Objects.nonNull(current) && Objects.nonNull(fc.function.arguments)) {
                current.function.arguments += fc.function.arguments;
            }
        }
        return result;
    }

    private void assignUsage(Usage usage, ChatCompletionChunk chunk) {
        chunk.usage().ifPresent(chunkUsage -> {
            usage.setCompletionTokens((int) chunkUsage.completionTokens());
            usage.setPromptTokens((int) chunkUsage.promptTokens());
            usage.setTotalTokens((int) chunkUsage.totalTokens());
        });

    }


    private ChatCompletionCreateParams toChatCompletionCreateParams(CompletionRequest request) {
        var builder = ChatCompletionCreateParams.builder().model(request.model).temperature(request.temperature).streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        //todo set max tokens
        request.messages.stream().map(this::fromMessage).forEach(builder::addMessage);
        if (request.tools != null && !request.tools.isEmpty()) {
            request.tools.stream()
                    .map(this::toFunctionDefinition)
                    .map(fd -> ChatCompletionFunctionTool.builder().function(fd).build())
                    .forEach(builder::addTool);
        }
        return builder.build();


    }

    private ChatCompletionMessageParam fromMessage(Message message) {
        if (message.role == RoleType.USER) {
            return ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                    .content(message.content)
                    .build()
            );
        }
        if (message.role == RoleType.ASSISTANT) {
            var aiBuilder = ChatCompletionAssistantMessageParam.builder()
                    .content(message.content);
            if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
                message.toolCalls.stream()
                        .map(t -> {
                            var fc = ChatCompletionMessageFunctionToolCall.Function.builder().name(t.function.name).arguments(t.function.arguments).build();
                            return ChatCompletionMessageFunctionToolCall.builder().id(t.id).function(fc).build();
                        })
                        .forEach(aiBuilder::addToolCall);
            }
            return ChatCompletionMessageParam.ofAssistant(aiBuilder.build());
        }
        if (message.role == RoleType.SYSTEM) {
            return ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder()
                    .content(message.content)
                    .build()
            );
        }
        if (message.role == RoleType.TOOL) {
            return ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                    .content(message.content)
                    .toolCallId(message.toolCallId)
                    .build()
            );
        }
        throw new IllegalArgumentException("Invalid role: " + message.role);

    }


    private FunctionDefinition toFunctionDefinition(Tool tool) {
        return FunctionDefinition.builder()
                .name(tool.function.name)
                .description(tool.function.description)
                .parameters(toFunctionParameters(tool.function))
                .build();
    }

    private FunctionParameters toFunctionParameters(Function function) {
        var jsonSchema = JsonUtil.toJson(function.parameters);
        var jsonMap = JsonUtil.toMap(jsonSchema)
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> JsonValue.from(entry.getValue())
                ));
        return FunctionParameters.builder()
                .additionalProperties(jsonMap)
                .build();
    }
}
