package ai.core.llm.providers;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.inner.AzureInferenceModelsUtil;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import com.azure.ai.inference.ChatCompletionsAsyncClient;
import com.azure.ai.inference.ChatCompletionsClient;
import com.azure.ai.inference.ChatCompletionsClientBuilder;
import com.azure.ai.inference.EmbeddingsClient;
import com.azure.ai.inference.EmbeddingsClientBuilder;
import com.azure.ai.inference.ModelServiceVersion;
import com.azure.ai.inference.models.EmbeddingEncodingFormat;
import com.azure.ai.inference.models.EmbeddingInputType;
import com.azure.ai.inference.models.StreamingChatChoiceUpdate;
import com.azure.ai.inference.models.StreamingChatCompletionsUpdate;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class AzureInferenceProvider extends LLMProvider {
    private final ChatCompletionsAsyncClient chatAsyncClient;
    private final ChatCompletionsClient chatClient;
    private final EmbeddingsClient embeddingsClient;

    public AzureInferenceProvider(LLMProviderConfig config, String apiKey, String endpoint, boolean azureKeyCredential) {
        super(config);
        var options = new HttpClientOptions();
        options.setConnectTimeout(Duration.ofMillis(1000));
        options.setReadTimeout(Duration.ofSeconds(120));
        options.setConnectionIdleTimeout(Duration.ofMinutes(5));
        if (!azureKeyCredential) {
            TokenCredential tokenCredential = _ -> Mono.just(new AccessToken(apiKey, OffsetDateTime.MAX));
            this.chatClient = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(tokenCredential).endpoint(endpoint).buildClient();
            this.chatAsyncClient = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(tokenCredential).endpoint(endpoint).buildAsyncClient();
            this.embeddingsClient = new EmbeddingsClientBuilder().serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(tokenCredential).endpoint(endpoint).buildClient();
        } else {
            var keyCredential = new AzureKeyCredential(apiKey);
            this.chatClient = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(keyCredential).endpoint(endpoint).buildClient();
            this.chatAsyncClient = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(keyCredential).endpoint(endpoint).buildAsyncClient();
            this.embeddingsClient = new EmbeddingsClientBuilder().serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(keyCredential).endpoint(endpoint).buildClient();
        }
    }

    @Override
    public CompletionResponse completion(CompletionRequest request) {
        request.model = getModel(request);
        var chatCompletion = chatClient.complete(AzureInferenceModelsUtil.toAzureRequest(request));
        return CompletionResponse.of(AzureInferenceModelsUtil.toChoice(chatCompletion.getChoices(), request.getName()), AzureInferenceModelsUtil.toUsage(chatCompletion.getUsage()));
    }

    @Override
    public CompletionResponse completionStream(CompletionRequest request, StreamingCallback callback) {
        request.model = getModel(request);
        var stream = chatAsyncClient.completeStream(AzureInferenceModelsUtil.toAzureRequest(request));
        var choices = new ArrayList<Choice>();
        var usage = new AtomicReference<>(new Usage(0, 0, 0));
        var latch = new CountDownLatch(1);
        var contentBuilder = new StringBuilder();
        var finishReason = new AtomicReference<String>();
        var role = new AtomicReference<RoleType>();
        var toolCalls = new ArrayList<FunctionCall>();

        stream.subscribe(
                completion -> handleDelta(completion, contentBuilder, finishReason, toolCalls, callback),
                error -> {
                    callback.onError(error);
                    latch.countDown();
                },
                () -> {
                    callback.onComplete();
                    // Remove null entries from toolCalls
                    toolCalls.removeIf(Objects::isNull);

                    // Build complete message
                    var message = new Message();
                    message.role = role.get() != null ? role.get() : RoleType.ASSISTANT;
                    message.content = contentBuilder.toString();
                    message.toolCalls = toolCalls.isEmpty() ? null : toolCalls;
                    message.name = request.getName();

                    // Build complete choice
                    var completeChoice = new Choice();
                    completeChoice.message = message;
                    completeChoice.finishReason = finishReason.get() != null ? FinishReason.valueOf(finishReason.get().toUpperCase(Locale.ROOT)) : FinishReason.STOP;

                    choices.add(completeChoice);
                    latch.countDown();
                }
        );

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onError(e);
        }

        return CompletionResponse.of(choices, usage.get());
    }

    private void handleDelta(StreamingChatCompletionsUpdate completion, StringBuilder contentBuilder, AtomicReference<String> finishReason, List<FunctionCall> toolCalls, StreamingCallback callback) {
        if (completion.getChoices() == null || completion.getChoices().isEmpty()) {
            return; // No choices in this chunk, skip processing
        }

        var choice = completion.getChoices().getFirst();

        // Capture finish reason
        if (choice.getFinishReason() != null) {
            finishReason.set(choice.getFinishReason().toString());
        }

        if (choice.getDelta() == null) return;

        // Accumulate content
        if (choice.getDelta().getContent() != null) {
            var content = choice.getDelta().getContent();
            contentBuilder.append(content);
            callback.onChunk(content);
        }

        // Merge tool calls by array index
        if (choice.getDelta().getToolCalls() != null) {
            mergeToolCalls(choice, toolCalls);
        }
    }

    private void mergeToolCalls(StreamingChatChoiceUpdate choice, List<FunctionCall> toolCalls) {
        for (int i = 0; i < choice.getDelta().getToolCalls().size(); i++) {
            var deltaToolCall = choice.getDelta().getToolCalls().get(i);

            // Ensure list is large enough
            while (toolCalls.size() <= i) {
                toolCalls.add(null);
            }

            var existingToolCall = toolCalls.get(i);
            if (existingToolCall == null) {
                // Create new tool call
                existingToolCall = new FunctionCall();
                existingToolCall.id = deltaToolCall.getId();
                existingToolCall.type = "function";
                existingToolCall.function = new FunctionCall.Function();
                existingToolCall.function.name = deltaToolCall.getFunction() != null ? deltaToolCall.getFunction().getName() : "";
                existingToolCall.function.arguments = deltaToolCall.getFunction() != null ? deltaToolCall.getFunction().getArguments() : "";
                toolCalls.set(i, existingToolCall);
            } else {
                if (existingToolCall.function.arguments == null) {
                    existingToolCall.function.arguments = "";
                }

                // Merge arguments incrementally
                if (deltaToolCall.getFunction() != null && deltaToolCall.getFunction().getArguments() != null) {
                    existingToolCall.function.arguments += deltaToolCall.getFunction().getArguments();
                }

                // Update id if it's provided in this chunk
                if (deltaToolCall.getId() != null && !deltaToolCall.getId().isEmpty()) {
                    existingToolCall.id = deltaToolCall.getId();
                }

                // Update function name if it's provided in this chunk
                if (deltaToolCall.getFunction() != null && deltaToolCall.getFunction().getName() != null) {
                    existingToolCall.function.name = deltaToolCall.getFunction().getName();
                }
            }
        }
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
    public int maxTokens() {
        return 128 * 1000;
    }

    @Override
    public String name() {
        return "azure-inference";
    }
}
