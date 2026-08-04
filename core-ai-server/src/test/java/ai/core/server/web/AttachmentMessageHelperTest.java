package ai.core.server.web;

import ai.core.api.server.session.SendMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttachmentMessageHelperTest {

    @Test
    void multimodalAttachmentPreservesOriginalUrl() {
        var attachment = new SendMessageRequest.SendMessageAttachment();
        attachment.url = "https://blob/photo.png";
        attachment.type = "IMAGE";
        attachment.fileName = "photo.png";
        attachment.contentType = "image/png";
        attachment.category = "multimodal";
        attachment.container = "uploads";
        attachment.blobName = "photo.png";
        var request = new SendMessageRequest();
        request.message = "look at this";
        request.attachments = List.of(attachment);

        var result = AttachmentMessageHelper.collectMultimodalAttachments(request);

        assertEquals("https://blob/photo.png", result.getFirst().get("url"));
    }
}
