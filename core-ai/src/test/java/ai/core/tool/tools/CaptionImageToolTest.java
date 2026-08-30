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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void multipleUrlsProduceMultipleImageParts() {
        var context = contextWithProvider();
        context.setMultiModalModel("vision-model");
        var params = "{\"query\": \"compare these\", \"urls\": [\"data:image/png;base64,QUJD\", \"data:image/png;base64,REVG\"]}";

        tool.execute(params, context);

        var content = llmProvider.lastRequest.messages.getFirst().content;
        var imageParts = content.stream().filter(c -> c.type == ai.core.llm.domain.Content.ContentType.IMAGE_URL).count();
        assertEquals(2, imageParts);
    }

    @Test
    void contextParamIsIncludedInQueryText() {
        var context = contextWithProvider();
        context.setMultiModalModel("vision-model");
        var params = "{\"query\": \"read the chart\", \"url\": \"data:image/png;base64,QUJD\", \"context\": \"user is comparing Q3 sales\"}";

        tool.execute(params, context);

        var text = llmProvider.lastRequest.messages.getFirst().content.getFirst().text;
        assertTrue(text.contains("user is comparing Q3 sales"));
    }

    @Test
    void attachLlmUsageAndCostToResult() {
        var context = contextWithProvider();
        context.setMultiModalModel("gpt-4o");

        var result = tool.execute(PARAMS, context);

        assertEquals("gpt-4o", result.getLlmModel());
        assertEquals(120, result.getLlmUsage().getPromptTokens());
        assertEquals(30, result.getLlmUsage().getCompletionTokens());
        assertTrue(result.getLlmCostUsd() > 0);
    }

    @Test
    void throwWhenNoUrlProvided() {
        var context = contextWithProvider();
        context.setMultiModalModel("vision-model");

        var exception = assertThrows(AgentRuntimeException.class, () -> tool.execute("{\"query\": \"describe\"}", context));

        assertEquals("CAPTION_IMAGE_TOOL_FAILED", exception.errorCode());
    }

    @Test
    void throwWhenNoVisionModelConfigured() {
        var context = contextWithProvider();
        context.setModel("deepseek/deepseek-v4-flash");

        var exception = assertThrows(AgentRuntimeException.class, () -> tool.execute(PARAMS, context));

        assertEquals("CAPTION_IMAGE_NO_VISION_MODEL", exception.errorCode());
    }

    @Test
    void serverLocalUrlsResolveToDataUriBeforeUpstream() {
        var context = ExecutionContext.builder()
                .sessionId("test-session")
                .customVariable(InternalUrlResolver.CONTEXT_KEY, (InternalUrlResolver) (url, method) ->
                    url.contains("/api/public/artifacts/")
                        ? new InternalUrlResolver.InternalUrlResult(200, "image/png", new byte[]{1, 2, 3})
                        : null)
                .build();
        context.setLlmProvider(llmProvider);
        context.setMultiModalModel("vision-model");

        tool.execute("{\"query\": \"describe\", \"url\": \"http://localhost:8080/api/public/artifacts/tok/content\"}", context);

        var message = llmProvider.lastRequest.messages.getLast();
        var image = message.content.stream().filter(content -> content.imageUrl != null).findFirst().orElseThrow();
        assertTrue(image.imageUrl.url.startsWith("data:image/png;base64,"),
            "server-local urls must be inlined — the upstream vision provider cannot reach localhost");
    }

    @Test
    void externalUrlsPassThroughUnchanged() {
        var context = ExecutionContext.builder()
                .sessionId("test-session")
                .customVariable(InternalUrlResolver.CONTEXT_KEY, (InternalUrlResolver) (url, method) -> null)
                .build();
        context.setLlmProvider(llmProvider);
        context.setMultiModalModel("vision-model");

        tool.execute("{\"query\": \"describe\", \"url\": \"https://example.com/image.png\"}", context);

        var message = llmProvider.lastRequest.messages.getLast();
        var image = message.content.stream().filter(content -> content.imageUrl != null).findFirst().orElseThrow();
        assertEquals("https://example.com/image.png", image.imageUrl.url);
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
            response.usage = new Usage(120, 30, 150);
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
