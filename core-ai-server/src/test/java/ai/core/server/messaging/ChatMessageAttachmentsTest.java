package ai.core.server.messaging;

import ai.core.agent.AttachedContent;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatMessageAttachmentsTest {
    @Test
    void videoHintCarriesTheReferenceBothForUnderstandingAndForPublishing() {
        var attachments = new ChatMessageAttachments(null, null, null);
        var video = AttachedContent.ofReference("video_1a2b3c4d-0000-0000-0000-000000000000", "video/mp4", "promo.mp4");

        var message = attachments.appendVideoHints("make a google post", List.of(video));

        assertTrue(message.contains("reference: video_1a2b3c4d-0000-0000-0000-000000000000"));
        assertTrue(message.contains("\"source\": \"video_1a2b3c4d-0000-0000-0000-000000000000\""));
        assertTrue(message.contains("\"public\": true"));
    }

    @Test
    void videoDownloadHintCarriesThePlatformUrlSoStagingCannotStrandTheAgent() {
        var attachments = new ChatMessageAttachments(null, null, null);
        var payload = Map.<String, Object>of("multimodalAttachments", List.of(Map.of(
                "type", "VIDEO",
                "fileName", "promo.mp4",
                "url", "https://blob.example.com/static/ai/uploads/abc.mp4")));

        var message = attachments.appendVideoDownloadHints("make a google post", payload);

        assertTrue(message.contains("https://blob.example.com/static/ai/uploads/abc.mp4"));
        assertTrue(message.contains("[Video download] promo.mp4"));
    }

    @Test
    void videoDownloadHintSignsAUrlWhenTheClientSentNone() {
        var storage = mock(ObjectStorageService.class);
        when(storage.generateDownloadCredential("multimodal", "ai/videos/promo.mp4")).thenReturn(
                new ObjectStorageService.DownloadCredential("https://signed.example.com/promo.mp4",
                        "multimodal", "ai/videos/promo.mp4", "2026-10-27T06:00:00Z"));
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(storage);
        var attachments = new ChatMessageAttachments(null, resolver, null);
        var payload = Map.<String, Object>of("multimodalAttachments", List.of(Map.of(
                "type", "VIDEO",
                "fileName", "promo.mp4",
                "container", "multimodal",
                "blobName", "ai/videos/promo.mp4")));

        var message = attachments.appendVideoDownloadHints("make a google post", payload);

        assertTrue(message.contains("https://signed.example.com/promo.mp4"));
    }
}
