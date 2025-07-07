package ai.core.llm;

import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;

/**
 * @author stephen
 */
public abstract class LLMProvider {
    public LLMProviderConfig config;

    public LLMProvider(LLMProviderConfig config) {
        this.config = config;
    }

    public void setConfig(LLMProviderConfig config) {
        this.config = config;
    }

    public abstract CompletionResponse completion(CompletionRequest request);
    public abstract EmbeddingResponse embeddings(EmbeddingRequest request);
    public abstract RerankingResponse rerankings(RerankingRequest request);

    public abstract CaptionImageResponse captionImage(CaptionImageRequest request);
    public abstract int maxTokens();
    public abstract String name();
}
