package ai.core.agent.internal;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Content;
import ai.core.tool.tools.GenerateImageTool;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attachment rendering fork: on the caption path a base64 image attachment is persisted
 * and referenced as text so it survives text-only models and history compression.
 *
 * @author Xander
 */
class AgentHelperAttachmentTest {
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02});

    @Test
    void base64ImageAttachmentStaysNativeWhenVisionNative() {
        var context = contextWithAttachment(null);

        var message = AgentHelper.buildUserMessage("look at this", context);

        assertTrue(hasImagePart(message.content));
    }

    @Test
    void base64ImageAttachmentBecomesReferenceTextOnCaptionPath() {
        var context = contextWithAttachment((fileName, contentType, bytes) -> "https://blob/attachment.png");
        context.setVisionNative(false);

        var message = AgentHelper.buildUserMessage("look at this", context);

        assertFalse(hasImagePart(message.content));
        var referenceText = message.content.stream()
                .filter(c -> c.type == Content.ContentType.TEXT)
                .map(c -> c.text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(referenceText.contains("https://blob/attachment.png"));
        assertTrue(referenceText.contains("caption_image"));
    }

    @Test
    void sourceBackedImageUsesOriginalUrlOnceOnCaptionPath() {
        var context = contextWithAttachment((fileName, contentType, bytes) -> "https://server/artifact.png");
        context.getAttachedContent().url = "https://blob/photo.png";
        context.setVisionNative(false);

        var message = AgentHelper.buildUserMessage("look at this\n\nhttps://blob/photo.png", context);

        var text = message.content.stream()
                .filter(c -> c.type == Content.ContentType.TEXT)
                .map(c -> c.text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertEquals(1, occurrences(text, "https://blob/photo.png"));
        assertFalse(text.contains("https://server/artifact.png"));
    }

    @Test
    void base64ImageAttachmentKeepsDataUriWhenNoSinkAvailable() {
        var context = contextWithAttachment(null);
        context.setVisionNative(false);

        var message = AgentHelper.buildUserMessage("look at this", context);

        assertTrue(hasImagePart(message.content));
    }

    @Test
    void urlImageAttachmentBecomesReferenceTextOnCaptionPath() {
        var context = ExecutionContext.builder().sessionId("test").build();
        context.setAttachedContents(List.of(AttachedContent.ofUrl(
                "https://blob/photo.png", AttachedContent.AttachedContentType.IMAGE)));
        context.setVisionNative(false);

        var message = AgentHelper.buildUserMessage("look at this", context);

        assertFalse(hasImagePart(message.content));
        var referenceText = message.content.stream()
                .filter(c -> c.type == Content.ContentType.TEXT)
                .map(c -> c.text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(referenceText.contains("https://blob/photo.png"));
        assertTrue(referenceText.contains("caption_image"));
    }

    @Test
    void base64PdfAttachmentBecomesReferenceTextOnCaptionPath() {
        var context = contextWithPdfAttachment((fileName, contentType, bytes) -> "https://blob/doc.pdf");
        context.setVisionNative(false);

        var message = AgentHelper.buildUserMessage("summarize this", context);

        assertFalse(hasFilePart(message.content));
        var referenceText = message.content.stream()
                .filter(c -> c.type == Content.ContentType.TEXT)
                .map(c -> c.text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(referenceText.contains("https://blob/doc.pdf"));
        assertTrue(referenceText.contains("summarize_pdf"));
    }

    @Test
    void base64PdfAttachmentStaysNativeWhenVisionNative() {
        var context = contextWithPdfAttachment(null);

        var message = AgentHelper.buildUserMessage("summarize this", context);

        assertTrue(hasFilePart(message.content));
    }

    private ExecutionContext contextWithPdfAttachment(GenerateImageTool.ImageOutputSink sink) {
        var builder = ExecutionContext.builder().sessionId("test");
        if (sink != null) builder.customVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, sink);
        var context = builder.build();
        context.setAttachedContents(List.of(AttachedContent.ofBase64(
                "QUJD", "application/pdf", AttachedContent.AttachedContentType.PDF, "doc.pdf")));
        return context;
    }

    private boolean hasFilePart(List<Content> content) {
        return content != null && content.stream().anyMatch(c -> c.type == Content.ContentType.FILE);
    }

    private ExecutionContext contextWithAttachment(GenerateImageTool.ImageOutputSink sink) {
        var builder = ExecutionContext.builder().sessionId("test");
        if (sink != null) builder.customVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, sink);
        var context = builder.build();
        context.setAttachedContents(List.of(AttachedContent.ofBase64(
                PNG_BASE64, "image/png", AttachedContent.AttachedContentType.IMAGE, "photo.png")));
        return context;
    }

    @Test
    void unreadableBase64AttachmentIsDroppedInsteadOfBeingSentToTheModel() {
        var errorBody = Base64.getEncoder().encodeToString("{\"errorCode\":\"NOT_FOUND\",\"message\":\"shared file not found\"}".getBytes(StandardCharsets.UTF_8));
        var context = ExecutionContext.builder().sessionId("test").build();
        context.setAttachedContents(List.of(AttachedContent.ofBase64(
                errorBody, "image/png", AttachedContent.AttachedContentType.IMAGE, "probe_teacher.png")));

        var message = AgentHelper.buildUserMessage("look at this", context);

        assertFalse(hasImagePart(message.content));
        var text = message.content.stream()
                .filter(c -> c.type == Content.ContentType.TEXT)
                .map(c -> c.text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("probe_teacher.png"));
        assertTrue(text.contains("not a readable image"));
    }

    private boolean hasImagePart(List<Content> content) {
        return content != null && content.stream().anyMatch(c -> c.type == Content.ContentType.IMAGE_URL);
    }

    private int occurrences(String value, String target) {
        return (value.length() - value.replace(target, "").length()) / target.length();
    }
}
