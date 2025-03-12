package ai.core.llm.providers;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.inner.AzureOpenAIModelsUtil;
import ai.core.llm.providers.inner.CaptionImageRequest;
import ai.core.llm.providers.inner.CaptionImageResponse;
import ai.core.llm.providers.inner.CompletionRequest;
import ai.core.llm.providers.inner.CompletionResponse;
import ai.core.llm.providers.inner.EmbeddingRequest;
import ai.core.llm.providers.inner.EmbeddingResponse;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.KeyCredential;
import core.framework.util.Strings;

import java.util.List;

/**
 * @author stephen
 */
public class AzureOpenAIProvider extends LLMProvider {
    private final OpenAIClient client;

    public AzureOpenAIProvider(LLMProviderConfig config, String apiKey, String endpoint) {
        super(config);
        if (endpoint == null) {
            // openai api key without endpoint
            this.client = new OpenAIClientBuilder().credential(new KeyCredential(apiKey)).buildClient();
        } else {
            this.client = new OpenAIClientBuilder().credential(new AzureKeyCredential(apiKey)).endpoint(endpoint).buildClient();
        }
    }

    @Override
    public CompletionResponse completion(CompletionRequest request) {
        request.model = getModel(request);
        var chatCompletion = client.getChatCompletions(request.model, AzureOpenAIModelsUtil.toAzureRequest(request));
        return new CompletionResponse(AzureOpenAIModelsUtil.toChoice(chatCompletion.getChoices(), request.messages.getLast().name), AzureOpenAIModelsUtil.toUsage(chatCompletion.getUsage()));
    }

    @Override
    public EmbeddingResponse embedding(EmbeddingRequest request) {
        var embeddingsOptions = new EmbeddingsOptions(List.of(request.query()));
        var embeddings = client.getEmbeddings(config.getEmbeddingModel(), embeddingsOptions);
        return AzureOpenAIModelsUtil.toEmbeddingResponse(embeddings);
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        var chatCompletion = client.getChatCompletions(request.model(), AzureOpenAIModelsUtil.toAzureRequest(request));
        return new CaptionImageResponse(chatCompletion.getChoices().getFirst().getMessage().getContent());
    }

    @Override
    public int maxTokens() {
        return 128 * 1000;
    }

    private String getModel(CompletionRequest request) {
        return Strings.isBlank(request.model) ? config.getModel() : request.model;
    }
}
