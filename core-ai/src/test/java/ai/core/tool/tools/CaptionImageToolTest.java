package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.streaming.StreamingCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Xander
 */
class CaptionImageToolTest {
    private static final String PARAMS = "{\"query\": \"describe the image\", \"url\": \"data:image/png;base64,QUJD\"}";

    private CaptionImageTool tool;
    private CapturingLLMProvider llmProvider;

    @BeforeEach
    void setUp() {
        tool = CaptionImageTool.builder().build();
        llmProvider = new CapturingLLMProvider();
    }

    @Test
    void useMultiModalModelWhenConfigured() {
        var context = contextWithProvider();
        context.setMultiModalModel("vision-model");
        context.setModel("text-model");

        tool.execute(PARAMS, context);

        assertEquals("vision-model", llmProvider.lastRequest.model);
    }

    @Test
    void useCaptionModelWhenMultiModalModelAbsent() {
        var context = ExecutionContext.builder()
                .sessionId("test-session")
                .customVariable("media.caption.model", "caption-model")
                .build();
        context.setLlmProvider(llmProvider);
        context.setModel("text-model");

        tool.execute(PARAMS, context);

        assertEquals("caption-model", llmProvider.lastRequest.model);
    }

    @Test
    void throwWhenNoVisionModelConfigured() {
        var context = contextWithProvider();
        context.setModel("deepseek/deepseek-v4-flash");

        var exception = assertThrows(AgentRuntimeException.class, () -> tool.execute(PARAMS, context));

        assertEquals("CAPTION_IMAGE_NO_VISION_MODEL", exception.errorCode());
    }

    private ExecutionContext contextWithProvider() {
        var context = ExecutionContext.builder().sessionId("test-session").build();
        context.setLlmProvider(llmProvider);
        return context;
    }

    static class CapturingLLMProvider extends LLMProvider {
        CompletionRequest lastRequest;

        CapturingLLMProvider() {
            super(new LLMProviderConfig("provider-default-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            lastRequest = request;
            var response = new CompletionResponse();
            response.choices = List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "a caption")));
            response.usage = new Usage();
            return response;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public int maxTokens() {
            return 4096;
        }

        @Override
        public String name() {
            return "capturing-llm";
        }
    }
}
