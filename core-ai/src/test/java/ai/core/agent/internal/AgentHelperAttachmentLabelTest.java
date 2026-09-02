package ai.core.agent.internal;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every non-video attachment is preceded by a short label part so the model can bind a name
 * the user typed ("menu.png", "the second image") to the right content, on both the native
 * vision path and the caption/tool path.
 *
 * @author Xander
 */
class AgentHelperAttachmentLabelTest {

    @Test
    void visionNativeImagesGetAdjacentLabelsInOrder() {
        var context = context(
                image("menu.png"),
                image("receipt.jpg"));

        var content = AgentHelper.buildUserMessage("compare them", context).content;

        assertEquals(List.of(
                Content.ContentType.TEXT,
                Content.ContentType.TEXT, Content.ContentType.IMAGE_URL,
                Content.ContentType.TEXT, Content.ContentType.IMAGE_URL), types(content));
        assertEquals("compare them", content.get(0).text);
        assertEquals("[Image 1: menu.png]", content.get(1).text);
        assertEquals("[Image 2: receipt.jpg]", content.get(3).text);
    }

    @Test
    void captionPathImageLabelPrecedesToolReferenceText() {
        var photo = AttachedContent.ofUrl("https://blob/photo.png", AttachedContent.AttachedContentType.IMAGE);
        photo.filename = "photo.png";
        var context = context(photo);
        context.setVisionNative(false);

        var content = AgentHelper.buildUserMessage("what is this", context).content;

        assertEquals(List.of(Content.ContentType.TEXT, Content.ContentType.TEXT, Content.ContentType.TEXT), types(content));
        assertEquals("[Image 1: photo.png]", content.get(1).text);
        assertTrue(content.get(2).text.contains("https://blob/photo.png"));
        assertTrue(content.get(2).text.contains("caption_image"));
    }

    @Test
    void pdfAttachmentGetsFileLabelWithItsOwnOrdinal() {
        var context = context(
                image("menu.png"),
                AttachedContent.ofBase64("QUJD", "application/pdf", AttachedContent.AttachedContentType.PDF, "report.pdf"));

        var content = AgentHelper.buildUserMessage("summarize", context).content;

        assertEquals("[Image 1: menu.png]", content.get(1).text);
        assertEquals("[File 1: report.pdf]", content.get(3).text);
        assertEquals(Content.ContentType.FILE, content.get(4).type);
    }

    @Test
    void labelOmitsNameWhenFilenameIsMissing() {
        var context = context(AttachedContent.ofBase64("QUJD", "image/png", AttachedContent.AttachedContentType.IMAGE));

        var content = AgentHelper.buildUserMessage("look", context).content;

        assertEquals("[Image 1]", content.get(1).text);
    }

    @Test
    void videoHintStaysAfterLabeledAttachmentsWithoutExtraLabel() {
        var context = context(
                AttachedContent.ofReference("video_1", "video/mp4", "demo.mp4"),
                image("menu.png"));

        var content = AgentHelper.buildUserMessage("look", context).content;

        assertEquals(List.of(
                Content.ContentType.TEXT,
                Content.ContentType.TEXT, Content.ContentType.IMAGE_URL,
                Content.ContentType.TEXT), types(content));
        assertEquals("[Image 1: menu.png]", content.get(1).text);
        assertTrue(content.get(3).text.startsWith("[Video attachment: demo.mp4]"));
    }

    private ExecutionContext context(AttachedContent... attachments) {
        var context = ExecutionContext.builder().sessionId("test").build();
        context.setAttachedContents(List.of(attachments));
        return context;
    }

    private AttachedContent image(String filename) {
        return AttachedContent.ofBase64("QUJD", "image/png", AttachedContent.AttachedContentType.IMAGE, filename);
    }

    private List<Content.ContentType> types(List<Content> content) {
        return content.stream().map(c -> c.type).toList();
    }
}
