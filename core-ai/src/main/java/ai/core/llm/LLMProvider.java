package ai.core.llm;

import ai.core.agent.streaming.DefaultStreamingCallback;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
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
        return completionStream(request, new DefaultStreamingCallback());
    }

    /**
     * Public streaming completion method with tracing support
     */
    public final CompletionResponse completionStream(CompletionRequest request, StreamingCallback callback) {
        request.model = getModel(request);
        preprocess(request);
        if (tracer != null) {
            return tracer.traceLLMCompletion(name(), request, () -> doCompletionStream(request, callback));
        }
        return doCompletionStream(request, callback);
    }

    public void preprocess(CompletionRequest dto) {
        dto.temperature = dto.temperature != null ? dto.temperature : config.getTemperature();
        if (dto.model.startsWith("o1") || dto.model.startsWith("o3")) {
            dto.temperature = null;
        }
        if (dto.model.startsWith("gpt-5")) {
            dto.temperature = 1.0;
        }
        dto.messages.forEach(message -> {
            if (message.role == RoleType.SYSTEM && dto.model.startsWith("o1")) {
                message.role = RoleType.USER;
            }
            if (message.role == RoleType.ASSISTANT && message.name == null) {
                message.name = "assistant";
            }
            if (message.toolCalls != null && message.toolCalls.isEmpty()) {
                message.toolCalls = null;
            }
        });
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

    public int maxTokens() {
        return LLMModelContextRegistry.getInstance().getMaxInputTokens(config.getModel());
    }

    public int maxTokens(String modelName) {
        return LLMModelContextRegistry.getInstance().getMaxInputTokens(modelName);
    }

    public int maxOutputTokens() {
        return LLMModelContextRegistry.getInstance().getMaxOutputTokens(config.getModel());
    }

    public int maxOutputTokens(String modelName) {
        return LLMModelContextRegistry.getInstance().getMaxOutputTokens(modelName);
    }

    public abstract String name();



    public String getModel(CompletionRequest request) {
        return Strings.isBlank(request.model) ? config.getModel() : request.model;
    }
}
