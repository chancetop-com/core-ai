package ai.core.llm.providers;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
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
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
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

        stream.subscribe(
                completion -> {
                    if (completion.getChoices() != null && !completion.getChoices().isEmpty()) {
                        var choice = completion.getChoices().getFirst();
                        if (choice.getDelta() != null && choice.getDelta().getContent() != null) {
                            callback.onChunk(choice.getDelta().getContent());
                        }
                    }
                },
                callback::onError,
                () -> {
                    callback.onComplete();
                    choices.addAll(AzureInferenceModelsUtil.toChoiceStream(Objects.requireNonNull(stream.blockLast()).getChoices(), request.getName()));
                    usage.set(AzureInferenceModelsUtil.toUsage(Objects.requireNonNull(stream.blockLast()).getUsage()));
                }
        );

        return CompletionResponse.of(choices, usage.get());
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
