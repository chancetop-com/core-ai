package ai.core.server.conversation;

import ai.core.api.server.session.SendMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationAttachmentsTest {
    private static final String SESSION = "session-1";
    private static final String MULTIMODAL = "static";
    private static final String SANDBOX = "sandbox";

    private static SendMessageRequest.SendMessageAttachment attachment(String category, String container, String blobName,
                                                                      String type, String contentType) {
        var attachment = new SendMessageRequest.SendMessageAttachment();
        attachment.url = "https://storage.example/" + blobName;
        attachment.category = category;
        attachment.container = container;
        attachment.blobName = blobName;
        attachment.type = type;
        attachment.contentType = contentType;
        attachment.fileName = "report.png";
        return attachment;
    }

    private static SendMessageRequest.SendMessageAttachment image() {
        return attachment("multimodal", MULTIMODAL, "ai/conv/" + SESSION + "/one.png", "IMAGE", "image/png");
    }

    private static SendMessageRequest.SendMessageAttachment sandboxFile() {
        return attachment("sandbox", SANDBOX, "uploads/conv/" + SESSION + "/one.docx", "FILE",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void webComposerDescriptorsAreAccepted() {
        assertNull(ConversationAttachments.validate(List.of(image(), sandboxFile()), SESSION, MULTIMODAL, SANDBOX));
        var pdf = attachment("multimodal", MULTIMODAL, "ai/conv/" + SESSION + "/one.pdf", "PDF", "application/pdf");
        assertNull(ConversationAttachments.validate(List.of(pdf), SESSION, MULTIMODAL, SANDBOX));
        assertNull(ConversationAttachments.validate(null, SESSION, MULTIMODAL, SANDBOX));
    }

    @Test
    void anotherConversationOrContainerIsRejected() {
        var other = attachment("multimodal", MULTIMODAL, "ai/conv/session-2/one.png", "IMAGE", "image/png");
        assertEquals("attachment does not belong to this conversation",
                ConversationAttachments.validate(List.of(other), SESSION, MULTIMODAL, SANDBOX));
        var wrongContainer = attachment("sandbox", MULTIMODAL, "uploads/conv/" + SESSION + "/one.docx", "FILE", "text/plain");
        assertEquals("attachment container is invalid",
                ConversationAttachments.validate(List.of(wrongContainer), SESSION, MULTIMODAL, SANDBOX));
    }

    @Test
    void typeAndCategoryMustAgree() {
        var imageAsSandbox = attachment("sandbox", SANDBOX, "uploads/conv/" + SESSION + "/one.png", "IMAGE", "image/png");
        assertTrue(ConversationAttachments.validate(List.of(imageAsSandbox), SESSION, MULTIMODAL, SANDBOX).contains("type is invalid"));
        var mismatchedContent = attachment("multimodal", MULTIMODAL, "ai/conv/" + SESSION + "/one.png", "IMAGE", "text/plain");
        assertTrue(ConversationAttachments.validate(List.of(mismatchedContent), SESSION, MULTIMODAL, SANDBOX).contains("type is invalid"));
        var unknownType = attachment("multimodal", MULTIMODAL, "ai/conv/" + SESSION + "/one.png", "AUDIO", "audio/mpeg");
        assertTrue(ConversationAttachments.validate(List.of(unknownType), SESSION, MULTIMODAL, SANDBOX).contains("type is invalid"));
    }

    @Test
    void countIsBounded() {
        var many = new ArrayList<SendMessageRequest.SendMessageAttachment>();
        for (var i = 0; i < ConversationAttachments.MAX_ATTACHMENTS + 1; i++) many.add(image());
        assertTrue(ConversationAttachments.validate(many, SESSION, MULTIMODAL, SANDBOX).contains("at most"));
    }

    @Test
    void fingerprintDistinguishesAttachmentSetsAndDrivesIdempotency() {
        var first = ConversationAttachments.fingerprint(List.of(image()));
        assertNotEquals(first, ConversationAttachments.fingerprint(List.of(sandboxFile())));
        assertNotEquals(first, ConversationAttachments.fingerprint(List.of()));
        assertEquals(first, ConversationAttachments.fingerprint(List.of(image())));
    }

    @Test
    void typesAreNormalizedForTheSessionRuntime() {
        var lower = attachment("multimodal", MULTIMODAL, "ai/conv/" + SESSION + "/one.png", "image", "image/png");
        ConversationAttachments.normalize(List.of(lower));
        assertEquals("IMAGE", lower.type);
        assertFalse(ConversationAttachments.fingerprint(List.of()).contains("null"));
    }
}
