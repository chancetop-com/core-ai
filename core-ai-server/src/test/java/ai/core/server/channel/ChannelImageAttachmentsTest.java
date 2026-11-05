package ai.core.server.channel;

import ai.core.agent.AttachedContent;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.messaging.ChatMessageAttachments;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class ChannelImageAttachmentsTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWZkAAAAASUVORK5CYII=");
    private static final byte[] GIF = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==");
    private static final int BYTE_LIMIT = 6 * 1024 * 1024;

    @Test
    void emptyInputNeedsNoStorage() {
        var fixture = fixture();

        assertEquals(List.of(), fixture.importer.store(List.of()));
        assertEquals(List.of(), fixture.importer.store(null));
        verifyNoInteractions(fixture.resolver, fixture.storage);
    }

    @Test
    void importedImagesReachTheExistingModelAttachmentConsumerInOrder() {
        var fixture = fixture();

        var metadata = fixture.importer.store(List.of(dataUri("image/png", PNG), dataUri("image/gif", GIF)));
        var contents = new ChatMessageAttachments(null, fixture.resolver, null)
                .contents(Map.of("multimodalAttachments", metadata), "session", "user");

        assertEquals(2, contents.size());
        assertEquals(AttachedContent.AttachedContentType.IMAGE, contents.getFirst().type);
        assertArrayEquals(PNG, Base64.getDecoder().decode(contents.getFirst().data));
        assertArrayEquals(GIF, Base64.getDecoder().decode(contents.getLast().data));
        assertEquals("image/png", contents.getFirst().mediaType);
        assertEquals("image/gif", contents.getLast().mediaType);
        assertTrue(contents.getFirst().url.startsWith("https://storage.example/download/ai/uploads/"));
        assertTrue(contents.getFirst().url.endsWith("?signature=test"));
        assertFalse(contents.getFirst().url.contains("data:"));
        for (var entry : metadata) {
            assertEquals("IMAGE", entry.get("type"));
            assertEquals("multimodal", entry.get("container"));
            assertTrue(entry.get("blobName").startsWith("ai/uploads/"));
            assertEquals(entry.get("blobName").substring("ai/uploads/".length()), entry.get("fileName"));
        }
    }

    @Test
    void usesTheConfiguredPublicEndpointInsteadOfAnInternalSignedUrl() {
        var fixture = fixture();
        when(fixture.storage.publicUrl(eq("multimodal"), anyString())).thenAnswer(invocation ->
                "https://media.example/" + invocation.getArgument(1));
        when(fixture.storage.generateDownloadCredential(anyString(), anyString()))
                .thenThrow(new IllegalStateException("internal signer must not be needed"));

        var metadata = fixture.importer.store(List.of(dataUri("image/png", PNG)));
        var content = new ChatMessageAttachments(null, fixture.resolver, null)
                .contents(Map.of("multimodalAttachments", metadata), "session", "user").getFirst();

        assertEquals("https://media.example/" + metadata.getFirst().get("blobName"), content.url);
        assertArrayEquals(PNG, Base64.getDecoder().decode(content.data));
    }

    @Test
    void rejectsInvalidSourcesBeforeWritingAnyObject() {
        var fixture = fixture();
        var invalidSources = Arrays.asList(null, "", "https://example.com/photo.png", "/tmp/photo.png",
                "data:image/png,plain-text", "data:image/png;base64,not-base64!", "data:image/png;base64,",
                dataUri("image/jpeg", PNG), dataUri("text/plain", PNG), dataUri("image/png", new byte[]{1, 2, 3}),
                dataUri("image/bmp", new byte[]{'B', 'M', 0, 0}));

        for (var source : invalidSources) {
            assertThrows(BadRequestException.class,
                    () -> fixture.importer.store(Arrays.asList(dataUri("image/png", PNG), source)));
        }

        assertTrue(fixture.objects.isEmpty());
        verifyNoInteractions(fixture.storage);
    }

    @Test
    void rejectsTooManyImagesBeforeStorage() {
        var fixture = fixture();

        assertThrows(BadRequestException.class,
                () -> fixture.importer.store(Collections.nCopies(11, dataUri("image/png", PNG))));

        verifyNoInteractions(fixture.storage);
    }

    @Test
    void boundsIndividualAndAggregateDecodedBytes() {
        var fixture = fixture();
        var oversized = Arrays.copyOf(PNG, BYTE_LIMIT + 1);
        var halfPlusOne = Arrays.copyOf(PNG, BYTE_LIMIT / 2 + 1);

        assertThrows(BadRequestException.class, () -> fixture.importer.store(List.of(dataUri("image/png", oversized))));
        assertThrows(BadRequestException.class, () -> fixture.importer.store(List.of(
                dataUri("image/png", halfPlusOne), dataUri("image/png", halfPlusOne))));

        verifyNoInteractions(fixture.storage);
    }

    @Test
    void removesEarlierObjectsWhenALaterUploadFails() {
        var fixture = fixture();
        var failure = new IllegalStateException("storage unavailable");
        doAnswer(invocation -> {
            if (!fixture.objects.isEmpty()) throw failure;
            fixture.objects.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(fixture.storage).uploadObject(eq("multimodal"), anyString(), any(byte[].class), anyString());

        var error = assertThrows(IllegalStateException.class,
                () -> fixture.importer.store(List.of(dataUri("image/png", PNG), dataUri("image/gif", GIF))));

        assertSame(failure, error);
        assertTrue(fixture.objects.isEmpty());
    }

    @Test
    void removesUploadedObjectWhenSigningFailsWithoutMaskingTheFailure() {
        var fixture = fixture();
        var failure = new IllegalStateException("signing unavailable");
        when(fixture.storage.generateDownloadCredential(anyString(), anyString())).thenThrow(failure);

        var error = assertThrows(IllegalStateException.class,
                () -> fixture.importer.store(List.of(dataUri("image/png", PNG))));

        assertSame(failure, error);
        assertTrue(fixture.objects.isEmpty());
    }

    @Test
    void preservesTheImportErrorIfCleanupAlsoFails() {
        var fixture = fixture();
        var failure = new IllegalStateException("signing unavailable");
        var cleanupFailure = new IllegalStateException("cleanup unavailable");
        when(fixture.storage.generateDownloadCredential(anyString(), anyString())).thenThrow(failure);
        doThrow(cleanupFailure).when(fixture.storage).deleteObject(anyString(), anyString());

        var error = assertThrows(IllegalStateException.class,
                () -> fixture.importer.store(List.of(dataUri("image/png", PNG))));

        assertSame(failure, error);
        assertArrayEquals(new Throwable[]{cleanupFailure}, error.getSuppressed());
    }

    @Test
    void rejectsAMissingDownloadUrlAndCleansTheObject() {
        var fixture = fixture();
        when(fixture.storage.generateDownloadCredential(anyString(), anyString())).thenReturn(
                new ObjectStorageService.DownloadCredential("", "multimodal", "unused", "later"));

        assertThrows(IllegalStateException.class, () -> fixture.importer.store(List.of(dataUri("image/png", PNG))));

        assertTrue(fixture.objects.isEmpty());
    }

    @Test
    void reportsUnconfiguredStorageExplicitly() {
        var fixture = fixture();
        when(fixture.resolver.resolve()).thenReturn(null);

        var error = assertThrows(IllegalStateException.class, () -> fixture.importer.store(List.of(dataUri("image/png", PNG))));

        assertTrue(error.getMessage().contains("storage"));
    }

    private String dataUri(String mime, byte[] bytes) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private Fixture fixture() {
        var storage = mock(ObjectStorageService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.multimodalContainer()).thenReturn("multimodal");
        Map<String, byte[]> objects = new HashMap<>();
        doAnswer(invocation -> {
            objects.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(storage).uploadObject(eq("multimodal"), anyString(), any(byte[].class), anyString());
        doAnswer(invocation -> {
            objects.remove(invocation.getArgument(1));
            return null;
        }).when(storage).deleteObject(eq("multimodal"), anyString());
        when(storage.generateDownloadCredential(eq("multimodal"), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            return new ObjectStorageService.DownloadCredential("https://storage.example/download/" + name + "?signature=test",
                    "multimodal", name, "2026-11-05T04:00:00Z");
        });
        when(storage.downloadObject(eq("multimodal"), anyString())).thenAnswer(invocation -> objects.get(invocation.getArgument(1)));
        when(storage.headObject(eq("multimodal"), anyString())).thenAnswer(invocation ->
                new ObjectStorageService.ObjectMetadata((long) objects.get(invocation.getArgument(1)).length, null, null, null));
        var importer = new ChannelImageAttachments();
        importer.objectStorageResolver = resolver;
        return new Fixture(importer, resolver, storage, objects);
    }

    private record Fixture(ChannelImageAttachments importer, ObjectStorageServiceResolver resolver,
                           ObjectStorageService storage, Map<String, byte[]> objects) {
    }
}
