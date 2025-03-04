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
import com.azure.core.credential.AzureKeyCredential;
import core.framework.util.Strings;

import java.util.List;

/**
 * @author stephen
 */
public class AzureInferenceProvider extends LLMProvider {
    private final ChatCompletionsClient client;
    private final EmbeddingsClient embeddingsClient;

    public AzureInferenceProvider(LLMProviderConfig config, String apiKey, String endpoint) {
        super(config);
        this.client = new ChatCompletionsClientBuilder().serviceVersion(ModelServiceVersion.V2024_05_01_PREVIEW).credential(new AzureKeyCredential(apiKey)).endpoint(endpoint).buildClient();
        this.embeddingsClient = new EmbeddingsClientBuilder().credential(new AzureKeyCredential(apiKey)).endpoint(endpoint).buildClient();
    }

    @Override
    public CompletionResponse completion(CompletionRequest request) {
        request.model = getModel(request);
        var chatCompletion = client.complete(AzureInferenceModelsUtil.toAzureRequest(request));
        return new CompletionResponse(AzureInferenceModelsUtil.toChoice(chatCompletion.getChoices()), AzureInferenceModelsUtil.toUsage(chatCompletion.getUsage()));
    }

    @Override
    public EmbeddingResponse embedding(EmbeddingRequest request) {
        var embeddings = embeddingsClient.embed(List.of(request.query()));
        return AzureInferenceModelsUtil.toEmbeddingResponse(embeddings);
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        var chatCompletion = client.complete(AzureInferenceModelsUtil.toAzureRequest(request));
        return new CaptionImageResponse(chatCompletion.getChoice().getMessage().getContent());
    }

    @Override
    public int maxTokens() {
        return 1000000;
    }

    private String getModel(CompletionRequest request) {
        return Strings.isBlank(request.model) ? config.getModel() : request.model;
    }
}
