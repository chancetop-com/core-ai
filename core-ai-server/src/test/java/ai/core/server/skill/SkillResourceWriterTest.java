package ai.core.server.skill;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SkillResource;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillResourceWriterTest {
    private static final String RUN_SH = "scripts/run.sh";
    private static final String FONT_TTF = "fonts/a.ttf";

    private static byte[] filled(char value, int size) {
        var bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private final Map<String, byte[]> uploaded = new HashMap<>();

    @Test
    void textResourceStaysInlineWithoutStorage() {
        var writer = new SkillResourceWriter(blobStore(false));
        var bytes = "#!/bin/sh".getBytes(StandardCharsets.UTF_8);

        var resources = writer.toResources("s1", "skill", Map.of(RUN_SH, bytes));

        var resource = resources.getFirst();
        assertEquals(RUN_SH, resource.path);
        assertEquals("#!/bin/sh", resource.content);
        assertNull(resource.storagePath);
        assertEquals((long) bytes.length, resource.size);
        assertEquals(SkillBlobStore.sha256(bytes), resource.sha256);
    }

    @Test
    void binaryResourceFailsWithoutStorage() {
        var writer = new SkillResourceWriter(blobStore(false));

        var error = assertThrows(BadRequestException.class, () -> writer.toResources("s1", "skill",
                Map.of(FONT_TTF, new byte[]{0x00, (byte) 0xFF})));

        assertTrue(error.getMessage().contains(FONT_TTF));
        assertTrue(error.getMessage().contains("object storage"));
    }

    @Test
    void binaryResourceGoesToBlobWithStorage() {
        var writer = new SkillResourceWriter(blobStore(true));
        var bytes = new byte[]{0x00, (byte) 0xFF};

        var resources = writer.toResources("s1", "skill", Map.of(FONT_TTF, bytes));

        var resource = resources.getFirst();
        assertNull(resource.content);
        assertTrue(resource.storagePath.startsWith("artifacts/skills/s1/resources/"));
        assertEquals(SkillBlobStore.sha256(bytes), resource.storagePath.substring(resource.storagePath.lastIndexOf('/') + 1));
        assertEquals("font/ttf", resource.contentType);
        assertEquals((long) bytes.length, resource.size);
        assertArrayEquals(bytes, uploaded.values().iterator().next());
    }

    @Test
    void oversizedTextResourceGoesToBlobWithStorage() {
        var writer = new SkillResourceWriter(blobStore(true));
        var bytes = filled('a', 1024 * 1024 + 1);

        var resources = writer.toResources("s1", "skill", Map.of("data/big.json", bytes));

        assertEquals(1, uploaded.size());
        assertNull(resources.getFirst().content);
    }

    @Test
    void oversizedTextResourceFallsBackInlineWithoutStorage() {
        var writer = new SkillResourceWriter(blobStore(false));
        var bytes = filled('a', 1024 * 1024 + 1);

        var resources = writer.toResources("s1", "skill", Map.of("data/big.json", bytes));

        assertEquals(bytes.length, resources.getFirst().content.getBytes(StandardCharsets.UTF_8).length);
        assertNull(resources.getFirst().storagePath);
    }

    @Test
    void budgetMovesLargestInlineResourcesToBlob() {
        var writer = new SkillResourceWriter(blobStore(true));
        var files = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 13; i++) {   // 13MB of inline text against a 12MB budget (empty SKILL.md keeps the math exact)
            files.put("data/file-" + i + ".txt", filled('a', 1024 * 1024));
        }

        var resources = writer.toResources("s1", "", files);

        assertEquals(13, resources.size());
        var blobs = resources.stream().filter(resource -> resource.storagePath != null).toList();
        assertEquals(1, blobs.size(), "exactly one resource must be externalized to fit the budget");
        assertEquals("data/file-0.txt", blobs.getFirst().path, "ties are externalized in path order");
    }

    @Test
    void mergeKeepsStoredResourcesAndValidatesRows() {
        var writer = new SkillResourceWriter(blobStore(false));
        var stored = inlineResource(RUN_SH, "#!/bin/sh");

        var merged = writer.merge("s1", List.of(stored), List.of(new SkillResourceUpdate(RUN_SH, true, null)));

        assertSame(stored, merged.getFirst());
        assertThrows(BadRequestException.class, () -> writer.merge("s1", List.of(stored),
                List.of(new SkillResourceUpdate("missing.md", true, null))));
        assertThrows(BadRequestException.class, () -> writer.merge("s1", List.of(stored),
                List.of(new SkillResourceUpdate(RUN_SH, false, null))));
        assertThrows(BadRequestException.class, () -> writer.merge("s1", List.of(stored),
                List.of(new SkillResourceUpdate(RUN_SH, true, null), new SkillResourceUpdate(RUN_SH, false, "x"))));
    }

    @Test
    void unchangedDetectsByteIdenticalResourceSets() {
        var stored = inlineResource(RUN_SH, "#!/bin/sh");

        assertTrue(SkillResourceWriter.unchanged(List.of(stored), List.of(stored)));
        assertTrue(SkillResourceWriter.unchanged(List.of(stored), List.of(inlineResource(RUN_SH, "#!/bin/sh"))));
        assertFalse(SkillResourceWriter.unchanged(List.of(stored), List.of(inlineResource(RUN_SH, "#!/bin/bash"))));
        assertFalse(SkillResourceWriter.unchanged(List.of(stored), List.of()));
        assertFalse(SkillResourceWriter.unchanged(List.of(stored), List.of(stored, stored)));
    }

    private SkillResource inlineResource(String path, String content) {
        var resource = new SkillResource();
        resource.path = path;
        resource.content = content;
        resource.size = (long) content.length();
        return resource;
    }

    private SkillBlobStore blobStore(boolean configured) {
        var storage = mock(ObjectStorageService.class);
        doAnswer(invocation -> {
            uploaded.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(storage).uploadObject(anyString(), anyString(), any(byte[].class), anyString());
        var resolver = mock(ObjectStorageServiceResolver.class);
        if (configured) {
            when(resolver.resolve()).thenReturn(storage);
            when(resolver.artifactContainer()).thenReturn("artifacts");
        }
        var blobStore = new SkillBlobStore();
        blobStore.storageResolver = resolver;
        return blobStore;
    }
}
