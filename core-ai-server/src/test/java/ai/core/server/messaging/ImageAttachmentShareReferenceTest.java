package ai.core.server.messaging;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class ImageAttachmentShareReferenceTest {
    @Test
    void persistsOwnedImageReferenceWithoutASandbox() {
        var storage = mock(ObjectStorageService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        var references = mock(SessionAttachmentRefRepository.class);
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.multimodalContainer()).thenReturn("uploads");
        var bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXcAAAAASUVORK5CYII=");
        when(storage.headObject("uploads", "ai/image.png")).thenReturn(
            new ObjectStorageService.ObjectMetadata((long) bytes.length, "etag", "image/png", null));
        when(storage.downloadObject("uploads", "ai/image.png")).thenReturn(bytes);
        var attachments = new ChatMessageAttachments(null, resolver, references);
        attachments.contents(Map.of("multimodalAttachments", List.of(Map.of("type", "IMAGE", "container", "uploads",
            "blobName", "ai/image.png", "contentType", "image/png", "fileName", "image.png"))), "session", "owner");
        var captured = ArgumentCaptor.forClass(SessionAttachmentRef.class);
        verify(references).insert(captured.capture());
        assertEquals("session", captured.getValue().sessionId);
        assertEquals("owner", captured.getValue().userId);
        assertEquals("ai/image.png", captured.getValue().blobName);
        assertEquals("etag", captured.getValue().sourceETag);
        assertEquals((long) bytes.length, captured.getValue().sourceSizeBytes);
    }
}
