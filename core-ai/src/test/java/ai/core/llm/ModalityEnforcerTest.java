package ai.core.llm;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class ModalityEnforcerTest {
    private static final ModelModalityRegistry IMAGE_UNSUPPORTED = (model, modality) ->
            modality == InputModality.TEXT ? ModalitySupport.SUPPORTED : ModalitySupport.UNSUPPORTED;
    private static final ModelModalityRegistry ALL_SUPPORTED = (model, modality) -> ModalitySupport.SUPPORTED;
    private static final ModelModalityRegistry ALL_UNKNOWN = (model, modality) ->
            modality == InputModality.TEXT ? ModalitySupport.SUPPORTED : ModalitySupport.UNKNOWN;

    @AfterEach
    void tearDown() {
        ModalityRuntimeOverrides.clear();
    }

    @Test
    void downgradeImageUrlToReferenceTextForUnsupportedModel() {
        var message = Message.of(new Message.MessageRecord(RoleType.TOOL,
                List.of(Content.of("look at this"), Content.of(Content.ImageUrl.of("https://blob/img.png", null))),
                null, "read_file", "call-1", null));

        var result = ModalityEnforcer.enforce(List.of(message), "text-model", IMAGE_UNSUPPORTED);

        assertEquals(1, result.downgradedCount());
        var enforced = result.messages().getFirst();
        assertEquals(RoleType.TOOL, enforced.role);
        assertEquals("call-1", enforced.toolCallId);
        assertEquals("look at this", enforced.content.get(0).text);
        assertEquals(Content.ContentType.TEXT, enforced.content.get(1).type);
        assertTrue(enforced.content.get(1).text.contains("https://blob/img.png"));
        assertTrue(enforced.content.get(1).text.contains("caption_image"));
    }

    @Test
    void downgradeDataUriImageToOmittedPlaceholder() {
        var message = Message.of(RoleType.USER, Content.of(Content.ImageUrl.of("data:image/png;base64,QUJD", "image/png")));

        var result = ModalityEnforcer.enforce(List.of(message), "text-model", IMAGE_UNSUPPORTED);

        var part = result.messages().getFirst().content.getFirst();
        assertEquals(Content.ContentType.TEXT, part.type);
        assertTrue(part.text.contains("omitted"));
        assertFalse(part.text.contains("base64"));
    }

    @Test
    void supportedModelKeepsMessagesUntouched() {
        var messages = List.of(Message.of(RoleType.USER, Content.of(Content.ImageUrl.of("https://blob/img.png", null))));

        var result = ModalityEnforcer.enforce(messages, "vision-model", ALL_SUPPORTED);

        assertSame(messages, result.messages());
        assertEquals(0, result.downgradedCount());
    }

    @Test
    void unknownModelPassesThroughAndFlags() {
        var messages = List.of(Message.of(RoleType.USER, Content.of(Content.ImageUrl.of("https://blob/img.png", null))));

        var result = ModalityEnforcer.enforce(messages, "mystery-model", ALL_UNKNOWN);

        assertSame(messages, result.messages());
        assertEquals(0, result.downgradedCount());
        assertTrue(result.unknownModalityPresent());
    }

    @Test
    void enforceIsIdempotent() {
        var message = Message.of(RoleType.USER, Content.of(Content.ImageUrl.of("https://blob/img.png", null)));

        var once = ModalityEnforcer.enforce(List.of(message), "text-model", IMAGE_UNSUPPORTED);
        var twice = ModalityEnforcer.enforce(once.messages(), "text-model", IMAGE_UNSUPPORTED);

        assertEquals(0, twice.downgradedCount());
        assertSame(once.messages(), twice.messages());
    }

    @Test
    void originalMessagesAreNotMutated() {
        var message = Message.of(RoleType.USER, Content.of(Content.ImageUrl.of("https://blob/img.png", null)));
        var messages = List.of(message);

        ModalityEnforcer.enforce(messages, "text-model", IMAGE_UNSUPPORTED);

        assertEquals(Content.ContentType.IMAGE_URL, message.content.getFirst().type);
    }

    @Test
    void downgradeFileContentForUnsupportedModel() {
        var base64File = Message.of(RoleType.USER, Content.ofFileBase64("QUJD", "application/pdf", "doc.pdf"));
        var urlFile = Message.of(RoleType.USER, Content.ofFileUrl("https://blob/doc.pdf"));

        var result = ModalityEnforcer.enforce(List.of(base64File, urlFile), "text-model", IMAGE_UNSUPPORTED);

        assertEquals(2, result.downgradedCount());
        assertTrue(result.messages().get(0).content.getFirst().text.contains("omitted"));
        var urlPart = result.messages().get(1).content.getFirst();
        assertTrue(urlPart.text.contains("https://blob/doc.pdf"));
        assertTrue(urlPart.text.contains("summarize_pdf"));
    }

    @Test
    void runtimeOverrideForcesDowngradeEvenWhenRegistryIsUnknown() {
        ModalityRuntimeOverrides.markUnsupported("mystery-model", InputModality.IMAGE);
        var messages = List.of(Message.of(RoleType.USER, Content.of(Content.ImageUrl.of("https://blob/img.png", null))));

        var result = ModalityEnforcer.enforce(messages, "mystery-model", ALL_UNKNOWN);

        assertEquals(1, result.downgradedCount());
        assertEquals(Content.ContentType.TEXT, result.messages().getFirst().content.getFirst().type);
    }
}
