package ai.core.llm;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the request-time modality enforcement wired into the LLMProvider base class,
 * including the passthrough exemption, the inline image budget and the auto-heal retries.
 *
 * @author Xander
 */
class LLMProviderModalityTest {
    private static final String PAYLOAD_TOO_LARGE = "invalid sse response, statusCode=413, content-type=text/html, "
            + "body=<html><head><title>413 Request Entity Too Large</title></head>"
            + "<body><center><h1>413 Request Entity Too Large</h1></center><hr><center>openresty</center></body></html>";

    private static final ModelModalityRegistry IMAGE_UNSUPPORTED = (model, modality) ->
            modality == InputModality.TEXT ? ModalitySupport.SUPPORTED : ModalitySupport.UNSUPPORTED;
    private static final ModelModalityRegistry ALL_UNKNOWN = (model, modality) ->
            modality == InputModality.TEXT ? ModalitySupport.SUPPORTED : ModalitySupport.UNKNOWN;

    private RecordingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RecordingProvider();
    }

    @AfterEach
    void tearDown() {
        ModalityRuntimeOverrides.clear();
    }

    @Test
    void downgradeImagesForKnownTextOnlyModel() {
        provider.setModalityRegistry(IMAGE_UNSUPPORTED);

        provider.completion(imageRequest("text-model"));

        assertEquals(1, provider.seenMessages.size());
        assertTrue(hasNoImagePart(provider.seenMessages.getFirst()));
    }

    @Test
    void passthroughRequestSkipsEnforcement() {
        provider.setModalityRegistry(IMAGE_UNSUPPORTED);
        var request = imageRequest("text-model");
        request.setPassthrough(true);

        provider.completion(request);

        assertTrue(hasImagePart(provider.seenMessages.getFirst()));
    }

    @Test
    void unknownModelPassesImagesThrough() {
        provider.setModalityRegistry(ALL_UNKNOWN);

        provider.completion(imageRequest("mystery-model"));

        assertTrue(hasImagePart(provider.seenMessages.getFirst()));
    }

    @Test
    void imageRejection400TriggersDowngradedRetry() {
        provider.setModalityRegistry(ALL_UNKNOWN);
        provider.failuresRemaining = 1;

        var response = provider.completion(imageRequest("mystery-model"));

        assertEquals("ok", response.choices.getFirst().message.content);
        assertEquals(2, provider.seenMessages.size());
        assertTrue(hasImagePart(provider.seenMessages.get(0)));
        assertTrue(hasNoImagePart(provider.seenMessages.get(1)));
        assertTrue(ModalityRuntimeOverrides.isMarkedUnsupported("mystery-model", InputModality.IMAGE));
    }

    @Test
    void incidentModelDowngradesThroughDefaultSeedRegistry() {
        // no setModalityRegistry call: exercises SeedModelModalityRegistry.INSTANCE end to end
        provider.completion(imageRequest("deepseek/deepseek-v4-pro"));

        assertTrue(hasNoImagePart(provider.seenMessages.getFirst()));
    }

    @Test
    void unrelated400IsNotRetried() {
        provider.setModalityRegistry(ALL_UNKNOWN);
        provider.failuresRemaining = 1;
        provider.failureMessage = "invalid sse response, statusCode=400, body={\"error\":{\"message\":\"context length exceeded\"}}";

        var request = imageRequest("mystery-model");
        assertThrows(RuntimeException.class, () -> provider.completion(request));

        assertEquals(1, provider.seenMessages.size());
    }

    @Test
    void requestTooLarge413RetriesWithoutInlineImages() {
        provider.setModalityRegistry(ALL_UNKNOWN);
        provider.failuresRemaining = 1;
        provider.failureMessage = PAYLOAD_TOO_LARGE;

        var response = provider.completion(inlineImageRequest("mystery-model", 1));

        assertEquals("ok", response.choices.getFirst().message.content);
        assertEquals(2, provider.seenMessages.size());
        assertTrue(hasImagePart(provider.seenMessages.get(0)));
        assertTrue(hasNoImagePart(provider.seenMessages.get(1)));
    }

    @Test
    void requestTooLarge413WithoutInlineImagesIsNotRetried() {
        provider.setModalityRegistry(ALL_UNKNOWN);
        provider.failuresRemaining = 1;
        provider.failureMessage = PAYLOAD_TOO_LARGE;
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, "hello")), List.of(), null, "mystery-model", "test");

        assertThrows(RuntimeException.class, () -> provider.completion(request));

        assertEquals(1, provider.seenMessages.size());
    }

    @Test
    void providerBudgetPrunesOlderInlineImages() {
        provider.setModalityRegistry(ALL_UNKNOWN);
        provider.config.setMaxInlineImages(1);

        provider.completion(inlineImageRequest("mystery-model", 2));

        assertEquals(1, provider.seenMessages.size());
        var messages = provider.seenMessages.getFirst();
        assertFalse(messageHasImagePart(messages.get(0)));
        assertTrue(messages.get(0).content.get(1).text.contains("Image omitted"));
        assertTrue(messageHasImagePart(messages.get(1)));
    }

    private CompletionRequest imageRequest(String model) {
        var message = Message.of(new Message.MessageRecord(RoleType.USER,
                List.of(Content.of("look"), Content.of(Content.ImageUrl.of("https://blob/img.png", null))),
                null, null, null, null));
        return CompletionRequest.of(List.of(message), List.of(), null, model, "test");
    }

    private CompletionRequest inlineImageRequest(String model, int images) {
        var messages = new ArrayList<Message>();
        for (var i = 0; i < images; i++) {
            messages.add(Message.of(new Message.MessageRecord(RoleType.USER,
                    List.of(Content.of("look " + i), Content.of(Content.ImageUrl.of("data:image/png;base64,QUJD", "image/png"))),
                    null, null, null, null)));
        }
        return CompletionRequest.of(messages, List.of(), null, model, "test");
    }

    private boolean hasImagePart(List<Message> messages) {
        return messages.stream().anyMatch(this::messageHasImagePart);
    }

    private boolean messageHasImagePart(Message message) {
        return message.content != null
                && message.content.stream().anyMatch(c -> c.type == Content.ContentType.IMAGE_URL);
    }

    private boolean hasNoImagePart(List<Message> messages) {
        return !hasImagePart(messages);
    }

    static class RecordingProvider extends LLMProvider {
        final List<List<Message>> seenMessages = new ArrayList<>();
        int failuresRemaining;
        String failureMessage = "invalid sse response, statusCode=400, content-type=application/json, "
                + "body={\"error\":{\"message\":\"Failed to deserialize the JSON body into the target type: "
                + "messages[1]: unknown variant `image_url`, expected `text` at line 1 column 746109\"}}";

        RecordingProvider() {
            super(new LLMProviderConfig("recording-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            return doCompletionStream(request, null);
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            seenMessages.add(request.messages);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new RuntimeException(failureMessage);
            }
            var response = new CompletionResponse();
            response.choices = List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok")));
            response.usage = new Usage();
            return response;
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
            return "recording-llm";
        }
    }
}
