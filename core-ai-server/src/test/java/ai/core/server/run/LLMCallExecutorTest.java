package ai.core.server.run;

import ai.core.api.server.run.LLMCallRequest;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.settings.SystemSettingsService;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMCallExecutorTest {
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02});

    @Test
    void usesSystemSettingsModelWhenDefinitionHasNoModel() {
        var executor = executor("deepseek-flash", "deepseek-v4-flash", "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition(null), "hello");

        assertEquals("deepseek-flash", stub.captured.model);
    }

    @Test
    void fallsBackToGatewayDefaultWhenSystemSettingsHasNoModel() {
        var executor = executor(null, "deepseek-v4-flash", "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition(null), "hello");

        assertEquals("deepseek-v4-flash", stub.captured.model);
    }

    @Test
    void fallsBackToLegacyConfigWhenGatewayHasNoDefaultModel() {
        var executor = executor(null, null, "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition(null), "hello");

        assertEquals("deepseek/deepseek-v4-flash", stub.captured.model);
    }

    @Test
    void treatsBlankModelAsUnset() {
        var executor = executor("deepseek-flash", null, "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition(" "), "hello");

        assertEquals("deepseek-flash", stub.captured.model);
    }

    @Test
    void definitionModelTakesPrecedenceOverDefaultModel() {
        var executor = executor("deepseek-flash", "deepseek-v4-flash", "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition("gpt-4o"), "hello");

        assertEquals("gpt-4o", stub.captured.model);
    }

    @Test
    void multipleImageAttachmentsProduceSingleLabeledUserMessage() {
        var executor = executor("deepseek-flash", null, null);
        var stub = stub(executor);
        var urlImage = urlAttachment(LLMCallRequest.AttachmentType.IMAGE, "https://example.com/first.png");
        var base64Image = base64Attachment(LLMCallRequest.AttachmentType.IMAGE, PNG_BASE64, "image/png");

        executor.execute(definition("gpt-4o"), "compare these images", List.of(urlImage, base64Image));

        assertEquals(1, stub.captured.messages.size());
        var userMessage = stub.captured.messages.getFirst();
        assertEquals(RoleType.USER, userMessage.role);
        var content = userMessage.content;
        assertEquals(List.of(
                Content.ContentType.TEXT,
                Content.ContentType.TEXT, Content.ContentType.IMAGE_URL,
                Content.ContentType.TEXT, Content.ContentType.IMAGE_URL),
                content.stream().map(c -> c.type).toList());
        assertEquals("compare these images", content.get(0).text);
        assertEquals("[Image 1]", content.get(1).text);
        assertEquals("https://example.com/first.png", content.get(2).imageUrl.url);
        assertEquals("[Image 2]", content.get(3).text);
        assertTrue(content.get(4).imageUrl.url.startsWith("data:image/png;base64,"));
    }

    @Test
    void usesMultiModalModelOnlyWhenAttachmentsArePresent() {
        var executor = executor("deepseek-flash", null, null);
        var stub = stub(executor);
        var def = definition("gpt-4o");
        def.multiModalModel = "gpt-4o-vision";
        var image = urlAttachment(LLMCallRequest.AttachmentType.IMAGE, "https://example.com/first.png");

        var withImages = executor.execute(def, "describe", List.of(image));
        assertEquals("gpt-4o-vision", stub.captured.model);
        assertEquals("gpt-4o-vision", withImages.model());

        var withoutAttachments = executor.execute(def, "hello", List.of());
        assertEquals("gpt-4o", stub.captured.model);
        assertEquals("gpt-4o", withoutAttachments.model());

        var nullAttachments = executor.execute(def, "hello", null);
        assertEquals("gpt-4o", stub.captured.model);
        assertEquals("gpt-4o", nullAttachments.model());
    }

    @Test
    void mixedImageAndPdfAttachmentsCountOrdinalsPerKind() {
        var executor = executor("deepseek-flash", null, null);
        var stub = stub(executor);
        var firstImage = urlAttachment(LLMCallRequest.AttachmentType.IMAGE, "https://example.com/first.png");
        var pdf = base64Attachment(LLMCallRequest.AttachmentType.PDF, "QUJD", "application/pdf");
        var secondImage = base64Attachment(LLMCallRequest.AttachmentType.IMAGE, PNG_BASE64, "image/png");

        executor.execute(definition("gpt-4o"), "review all", List.of(firstImage, pdf, secondImage));

        assertEquals(1, stub.captured.messages.size());
        var content = stub.captured.messages.getFirst().content;
        assertEquals(List.of(
                Content.ContentType.TEXT,
                Content.ContentType.TEXT, Content.ContentType.IMAGE_URL,
                Content.ContentType.TEXT, Content.ContentType.FILE,
                Content.ContentType.TEXT, Content.ContentType.IMAGE_URL),
                content.stream().map(c -> c.type).toList());
        assertEquals("review all", content.get(0).text);
        assertEquals("[Image 1]", content.get(1).text);
        assertEquals("https://example.com/first.png", content.get(2).imageUrl.url);
        assertEquals("[File 1]", content.get(3).text);
        assertEquals("[Image 2]", content.get(5).text);
        assertTrue(content.get(6).imageUrl.url.startsWith("data:image/png;base64,"));
    }

    @Test
    void rejectsVideoAttachmentWithIllegalArgumentException() {
        var executor = executor("deepseek-flash", null, null);
        stub(executor);
        var video = urlAttachment(LLMCallRequest.AttachmentType.VIDEO, "https://example.com/clip.mp4");

        var error = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(definition("gpt-4o"), "watch", List.of(video)));

        assertEquals("video attachments are not supported by LLM call", error.getMessage());
    }

    @Test
    void rejectsFileAttachmentWithIllegalArgumentException() {
        var executor = executor("deepseek-flash", null, null);
        stub(executor);
        var file = urlAttachment(LLMCallRequest.AttachmentType.FILE, "https://example.com/data.csv");

        var error = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(definition("gpt-4o"), "read", List.of(file)));

        assertEquals("file attachments are not supported by LLM call", error.getMessage());
    }

    private LLMCallRequest.Attachment urlAttachment(LLMCallRequest.AttachmentType type, String url) {
        var attachment = new LLMCallRequest.Attachment();
        attachment.type = type;
        attachment.url = url;
        return attachment;
    }

    private LLMCallRequest.Attachment base64Attachment(LLMCallRequest.AttachmentType type, String data, String mediaType) {
        var attachment = new LLMCallRequest.Attachment();
        attachment.type = type;
        attachment.data = data;
        attachment.mediaType = mediaType;
        return attachment;
    }

    private LLMCallExecutor executor(String settingsModel, String gatewayDefaultModel, String legacyModel) {
        var executor = new LLMCallExecutor();
        var gateway = mock(GatewayRoutingEngine.class);
        var settings = mock(SystemSettingsService.class);
        when(gateway.defaultChatModelId()).thenReturn(gatewayDefaultModel);
        when(settings.configuredLlmModel()).thenReturn(settingsModel);
        when(settings.llmModel()).thenReturn(legacyModel);
        executor.gatewayRoutingEngine = gateway;
        executor.systemSettingsService = settings;
        return executor;
    }

    private CapturingProvider stub(LLMCallExecutor executor) {
        var providers = mock(LLMProviders.class);
        var stub = new CapturingProvider();
        when(providers.getProvider()).thenReturn(stub);
        executor.llmProviders = providers;
        return stub;
    }

    private AgentDefinition definition(String model) {
        var definition = new AgentDefinition();
        definition.id = "llm-1";
        definition.userId = "user-1";
        definition.name = "test-llm-call";
        definition.type = DefinitionType.LLM_CALL;
        definition.model = model;
        definition.publishedConfig = null;
        definition.responseSchema = null;
        return definition;
    }

    private static final class CapturingProvider extends LLMProvider {
        CompletionRequest captured;

        CapturingProvider() {
            super(new LLMProviderConfig(null, null, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            captured = request;
            return response();
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            captured = request;
            return response();
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            return null;
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            return null;
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            return null;
        }

        @Override
        public String name() {
            return "stub";
        }

        private CompletionResponse response() {
            return CompletionResponse.of(
                    List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))),
                    new Usage(1, 1, 2)
            );
        }
    }
}
