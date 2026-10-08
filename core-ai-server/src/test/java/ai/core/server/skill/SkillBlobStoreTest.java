package ai.core.server.skill;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SkillResource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillBlobStoreTest {
    @Test
    void contentTypeComesFromTheExtensionWithAFallback() {
        assertEquals("font/ttf", SkillBlobStore.contentTypeOf("canvas-fonts/WorkSans-Bold.ttf"));
        assertEquals("text/plain", SkillBlobStore.contentTypeOf("docs/readme.TXT"));
        assertEquals("application/octet-stream", SkillBlobStore.contentTypeOf("scripts/run"));
        assertEquals("application/octet-stream", SkillBlobStore.contentTypeOf("weird."));
        assertEquals("application/octet-stream", SkillBlobStore.contentTypeOf(null));
    }

    @Test
    void sha256IsLowercaseHex() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                SkillBlobStore.sha256("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void deleteReplacedOnlyRemovesUnreferencedBlobs() {
        var storage = mock(ObjectStorageService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(storage);
        var blobStore = new SkillBlobStore();
        blobStore.storageResolver = resolver;
        var kept = blob("a");
        var removed = blob("b");

        blobStore.deleteReplaced(List.of(kept, removed), List.of(kept));

        verify(storage).deleteObject("artifacts", "skills/1/resources/b");
        verify(storage, never()).deleteObject(eq("artifacts"), eq("skills/1/resources/a"));
    }

    private SkillResource blob(String name) {
        var resource = new SkillResource();
        resource.path = name;
        resource.storagePath = "artifacts/skills/1/resources/" + name;
        return resource;
    }
}
