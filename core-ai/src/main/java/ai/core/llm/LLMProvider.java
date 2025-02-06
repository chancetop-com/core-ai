package ai.core.llm;

import ai.core.llm.providers.inner.CaptionImageRequest;
import ai.core.llm.providers.inner.CaptionImageResponse;
import ai.core.llm.providers.inner.CompletionRequest;
import ai.core.llm.providers.inner.CompletionResponse;
import ai.core.llm.providers.inner.EmbeddingRequest;
import ai.core.llm.providers.inner.EmbeddingResponse;

/**
 * @author stephen
 */
public abstract class LLMProvider {
    public LLMProviderConfig config;

    public LLMProvider(LLMProviderConfig config) {
        this.config = config;
    }

    public abstract CompletionResponse completion(CompletionRequest request);
    public abstract EmbeddingResponse embedding(EmbeddingRequest request);
    public abstract CaptionImageResponse captionImage(CaptionImageRequest request);
    public abstract int maxTokens();
}
