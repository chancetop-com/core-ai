package ai.core.llm;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.telemetry.LLMTracer;
import ai.core.telemetry.Tracer;
import core.framework.util.Strings;

/**
 * @author stephen
 */
public abstract class LLMProvider {
    public LLMProviderConfig config;
    protected LLMTracer tracer;

    public LLMProvider(LLMProviderConfig config) {
        this.config = config;
    }

    public void setConfig(LLMProviderConfig config) {
        this.config = config;
    }

    public void setTracer(LLMTracer tracer) {
        this.tracer = tracer;
    }

    public LLMTracer getTracer() {
        return tracer;
    }

    /**
     * Get tracer as base Tracer type for fallback scenarios
     */
    public Tracer getBaseTracer() {
        return tracer;
    }

    /**
     * Public completion method with tracing support
     */
    public final CompletionResponse completion(CompletionRequest request) {
        request.model = getModel(request);
        if (tracer != null) {
            return tracer.traceLLMCompletion(name(), request, () -> doCompletion(request));
        }
        return doCompletion(request);
    }

    /**
     * Public streaming completion method with tracing support
     */
    public final CompletionResponse completionStream(CompletionRequest request, StreamingCallback callback) {
        request.model = getModel(request);
        if (tracer != null) {
            return tracer.traceLLMCompletion(name(), request, () -> doCompletionStream(request, callback));
        }
        return doCompletionStream(request, callback);
    }

    /**
     * Subclasses implement this method for actual completion logic
     */
    protected abstract CompletionResponse doCompletion(CompletionRequest request);

    /**
     * Subclasses implement this method for actual streaming completion logic
     */
    protected abstract CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback);

    public abstract EmbeddingResponse embeddings(EmbeddingRequest request);
    public abstract RerankingResponse rerankings(RerankingRequest request);

    public abstract CaptionImageResponse captionImage(CaptionImageRequest request);
    public abstract int maxTokens();
    public abstract String name();



    public String getModel(CompletionRequest request) {
        return Strings.isBlank(request.model) ? config.getModel() : request.model;
    }
}
