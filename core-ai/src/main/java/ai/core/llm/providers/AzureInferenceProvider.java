package ai.core.llm.providers;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.inner.AzureInferenceModelsUtil;
import ai.core.llm.providers.inner.CaptionImageRequest;
import ai.core.llm.providers.inner.CaptionImageResponse;
import ai.core.llm.providers.inner.CompletionRequest;
import ai.core.llm.providers.inner.CompletionResponse;
import ai.core.llm.providers.inner.EmbeddingRequest;
import ai.core.llm.providers.inner.EmbeddingResponse;
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
import core.framework.util.Strings;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * @author stephen
 */
public class AzureInferenceProvider extends LLMProvider {
    private final ChatCompletionsClient chatClient;
    private final EmbeddingsClient embeddingsClient;

    public AzureInferenceProvider(LLMProviderConfig config, String apiKey, String endpoint, boolean azureKeyCredential) {
        super(config);
        var options = new HttpClientOptions();
        options.setConnectTimeout(Duration.ofMillis(1000));
        options.setReadTimeout(Duration.ofSeconds(120));
        options.setConnectionIdleTimeout(Duration.ofMinutes(5));
        if (!azureKeyCredential) {
            TokenCredential tokenCredential = request -> Mono.just(new AccessToken(apiKey, OffsetDateTime.MAX));
            this.chatClient = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(tokenCredential).endpoint(endpoint).buildClient();
            this.embeddingsClient = new EmbeddingsClientBuilder().serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(tokenCredential).endpoint(endpoint).buildClient();
        } else {
            var keyCredential = new AzureKeyCredential(apiKey);
            this.chatClient = new ChatCompletionsClientBuilder().httpClient(HttpClient.createDefault(options)).serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(keyCredential).endpoint(endpoint).buildClient();
            this.embeddingsClient = new EmbeddingsClientBuilder().serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(keyCredential).endpoint(endpoint).buildClient();
        }
    }

    @Override
    public CompletionResponse completion(CompletionRequest request) {
        request.model = getModel(request);
        var chatCompletion = chatClient.complete(AzureInferenceModelsUtil.toAzureRequest(request));
        return new CompletionResponse(AzureInferenceModelsUtil.toChoice(chatCompletion.getChoices(), request.name), AzureInferenceModelsUtil.toUsage(chatCompletion.getUsage()));
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        var rsp = embeddingsClient.embed(request.query(), 1536, EmbeddingEncodingFormat.FLOAT, EmbeddingInputType.TEXT, config.getEmbeddingModel(), null);
        return AzureInferenceModelsUtil.toEmbeddingResponse(request, rsp);
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

    private String getModel(CompletionRequest request) {
        return Strings.isBlank(request.model) ? config.getModel() : request.model;
    }
}
