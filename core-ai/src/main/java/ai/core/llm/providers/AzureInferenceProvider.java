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
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.inner.AzureInferenceModelsUtil;
import com.azure.ai.inference.ChatCompletionsClient;
import com.azure.ai.inference.ChatCompletionsClientBuilder;
import com.azure.ai.inference.EmbeddingsClient;
import com.azure.ai.inference.EmbeddingsClientBuilder;
import com.azure.ai.inference.ModelServiceVersion;
import com.azure.ai.inference.models.EmbeddingEncodingFormat;
import com.azure.ai.inference.models.EmbeddingInputType;
import com.azure.ai.inference.models.StreamingChatResponseToolCallUpdate;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * @author stephen
 */
public class AzureInferenceProvider extends LLMProvider {
    private final Logger logger = LoggerFactory.getLogger(AzureInferenceProvider.class);
    private final ChatCompletionsClient chatClient;
    private final EmbeddingsClient embeddingsClient;

    public AzureInferenceProvider(LLMProviderConfig config, String apiKey, String endpoint, boolean azureKeyCredential) {
        this(config, apiKey, endpoint, azureKeyCredential, ModelServiceVersion.getLatest());
    }

    public AzureInferenceProvider(LLMProviderConfig config, String apiKey, String endpoint, boolean azureKeyCredential, ModelServiceVersion apiVersion) {
        super(config);
        var options = new HttpClientOptions();
        options.setConnectTimeout(config.getConnectTimeout());
        options.setReadTimeout(config.getTimeout());
        options.setResponseTimeout(config.getTimeout());
        options.setConnectionIdleTimeout(Duration.ofMinutes(5));
        ChatCompletionsClientBuilder chatBuilder;
        EmbeddingsClientBuilder embeddingsBuilder;
        if (!azureKeyCredential) {
            TokenCredential tokenCredential = (TokenRequestContext context) -> Mono.just(new AccessToken(apiKey, OffsetDateTime.MAX));
            chatBuilder = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(apiVersion).credential(tokenCredential).endpoint(endpoint);
            embeddingsBuilder = new EmbeddingsClientBuilder().serviceVersion(apiVersion).credential(tokenCredential).endpoint(endpoint);
        } else {
            var keyCredential = new AzureKeyCredential(apiKey);
            chatBuilder = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(apiVersion).credential(keyCredential).endpoint(endpoint);
            embeddingsBuilder = new EmbeddingsClientBuilder().serviceVersion(apiVersion).credential(keyCredential).endpoint(endpoint);
        }
        this.chatClient = chatBuilder.buildClient();
        this.embeddingsClient = embeddingsBuilder.buildClient();
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        var chatCompletion = chatClient.complete(AzureInferenceModelsUtil.toAzureRequest(request));
        return CompletionResponse.of(AzureInferenceModelsUtil.toChoice(chatCompletion.getChoices(), request.getName()), AzureInferenceModelsUtil.toUsage(chatCompletion.getUsage()));
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        // Use sync client to ensure callback executes on the same thread
        var stream = chatClient.completeStream(AzureInferenceModelsUtil.toAzureRequest(request));
        var choices = new ArrayList<Choice>();
        var usage = new Usage(0, 0, 0);
        var contentBuilder = new StringBuilder();
        String finishReason = null;
        var toolCallUpdates = new ArrayList<StreamingChatResponseToolCallUpdate>();

        try {
            for (var completion : stream) {
                // Capture usage if available (typically in the final chunk)
                if (completion.getUsage() != null) {
                    usage = AzureInferenceModelsUtil.toUsage(completion.getUsage());
                }

                if (completion.getChoices() == null || completion.getChoices().isEmpty()) {
                    continue;
                }

                var choice = completion.getChoices().getFirst();

                // Capture finish reason
                if (choice.getFinishReason() != null) {
                    finishReason = choice.getFinishReason().toString();
                }

                if (choice.getDelta() == null) continue;

                // Accumulate content and invoke callback on main thread
                if (choice.getDelta().getContent() != null) {
                    var content = choice.getDelta().getContent();
                    contentBuilder.append(content);
                    callback.onChunk(content);
                }

                // Merge tool calls by array index
                if (choice.getDelta().getToolCalls() != null) {
                    toolCallUpdates.addAll(choice.getDelta().getToolCalls());
                }
            }

            // Stream completed successfully
            callback.onComplete();


            // Build complete message
            var message = new Message();
            message.role = RoleType.ASSISTANT;
            message.content = contentBuilder.toString();
            message.toolCalls = toolCallUpdates.isEmpty() ? null : toFc(toolCallUpdates);
            message.name = request.getName();

            // Build complete choice
            var completeChoice = new Choice();
            completeChoice.message = message;
            completeChoice.finishReason = finishReason != null ? FinishReason.valueOf(finishReason.toUpperCase(Locale.ROOT)) : FinishReason.STOP;

            choices.add(completeChoice);

        } catch (Exception e) {
            logger.error("Error in streaming completion: {}", e.getMessage(), e);
            callback.onError(e);
            throw new RuntimeException("Streaming failed", e);
        }

        return CompletionResponse.of(choices, usage);
    }

    private List<FunctionCall> toFc(List<StreamingChatResponseToolCallUpdate> toolCalls) {
        var fcs = toolCalls
                .stream()
                .map(t -> FunctionCall.of(t.getId(), "function", t.getFunction().getName(), t.getFunction().getArguments()))
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

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        var rsp = embeddingsClient.embed(request.query(), 1536, EmbeddingEncodingFormat.FLOAT, EmbeddingInputType.TEXT, config.getEmbeddingModel(), null);
        return AzureInferenceModelsUtil.toEmbeddingResponse(request, rsp);
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) {
        return null;
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        var captionRsp = chatClient.complete(AzureInferenceModelsUtil.toAzureRequest(request));
        return new CaptionImageResponse(captionRsp.getChoice().getMessage().getContent(), AzureInferenceModelsUtil.toUsage(captionRsp.getUsage()));
    }

    @Override
    public String name() {
        return "azure-inference";
    }
}
