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
public interface LLMProvider {
    CompletionResponse completion(CompletionRequest request);
    EmbeddingResponse embedding(EmbeddingRequest request);
    CaptionImageResponse captionImage(CaptionImageRequest request);
    int maxTokens();
}
