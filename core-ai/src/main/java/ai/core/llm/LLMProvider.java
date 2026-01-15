package ai.core.llm;

import ai.core.agent.streaming.DefaultStreamingCallback;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.StreamOptions;
import ai.core.telemetry.LLMTracer;
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

    public final CompletionResponse completion(CompletionRequest request) {
        return completionStream(request, new DefaultStreamingCallback());
    }

    public final CompletionResponse completionStream(CompletionRequest request, StreamingCallback callback) {
        request.model = getModel(request);
        preprocess(request);
        request.stream = true;
        request.streamOptions = new StreamOptions();
        CompletionResponse response;
        if (tracer != null) {
            response = tracer.traceLLMCompletion(name(), request, () -> doCompletionStream(request, callback));
        } else {
            response = doCompletionStream(request, callback);
        }
        postprocess(request, response);
        return response;
    }

    public void preprocess(CompletionRequest dto) {
        dto.temperature = dto.temperature != null ? dto.temperature : config.getTemperature();
        if (dto.model.startsWith("o1") || dto.model.startsWith("o3")) {
            dto.temperature = null;
        }
        if (dto.model.contains("gpt-5")) {
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

    private void postprocess(CompletionRequest request, CompletionResponse response) {
        // todo: reasoning effort finish reason handling can be more generic
        var m = response.choices.getFirst().message;
        if (request.reasoningEffort != null && !m.toolCalls.isEmpty()) {
            response.choices.getFirst().finishReason = FinishReason.TOOL_CALLS;
        }
        if (request.model.contains("gpt-5") && !Strings.isBlank(m.reasoningContent)) {
            m.content = Strings.format("<think>{}</think>\n{}", m.reasoningContent, m.content);
        }
    }

    protected abstract CompletionResponse doCompletion(CompletionRequest request);

    protected abstract CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback);

    public abstract EmbeddingResponse embeddings(EmbeddingRequest request);
    public abstract RerankingResponse rerankings(RerankingRequest request);

    public abstract CaptionImageResponse captionImage(CaptionImageRequest request);

    public int maxTokens() {
        return LLMModelContextRegistry.getInstance().getMaxInputTokens(config.getModel());
    }

    public abstract String name();

    public String getModel(CompletionRequest request) {
        return Strings.isBlank(request.model) ? config.getModel() : request.model;
    }
}
