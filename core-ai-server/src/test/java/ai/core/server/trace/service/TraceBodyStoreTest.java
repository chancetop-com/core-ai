package ai.core.server.trace.service;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceBodyStoreTest {
    private final Map<String, byte[]> objects = new HashMap<>();
    private final ObjectStorageService storage = storage();
    private TraceBodyStore store;

    @BeforeEach
    void setUp() {
        objects.clear();
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.artifactContainer()).thenReturn("artifacts");
        store = new TraceBodyStore();
        store.objectStorageResolver = resolver;
        store.configure("true", 16 * 1024, "trace-container", "dev-ai", true);
    }

    @Test
    void offloadsOversizedBodyAndHydratesExactContent() {
        var body = "x".repeat(64 * 1024);

        var reference = store.offload("trace-1", "span-1", "in", body);

        assertTrue(store.isRef(reference));
        assertTrue(reference.contains("$coreai_blob_ref"));
        assertEquals(1, objects.size());
        var key = objects.keySet().iterator().next();
        var month = YearMonth.now(ZoneOffset.UTC).toString();
        assertEquals("dev-ai/trace-bodies/" + month + "/trace-1/span-1.in.json.gz", key);
        assertEquals(body, store.hydrate(reference, true, Long.MAX_VALUE));
        assertEquals(body, store.body(reference));
    }

    @Test
    void keepsSmallBodyInline() {
        var body = "x".repeat(16 * 1024);

        assertEquals(body, store.offload("trace-1", "span-1", "in", body));
        assertTrue(objects.isEmpty());
    }

    @Test
    void keepsReferenceWhenHydrationBudgetIsExceeded() {
        var body = "x".repeat(3 * 1024 * 1024);
        var reference = store.offload("trace-1", "span-1", "in", body);

        assertEquals(reference, store.hydrate(reference, false, 2 * 1024 * 1024));
        assertEquals(body, store.hydrate(reference, true, Long.MAX_VALUE));
    }

    @Test
    void storesLastUserMessageAsPreview() {
        var body = "{\"messages\":[{\"role\":\"system\",\"content\":\"long system prompt\"},"
            + "{\"role\":\"user\",\"content\":\"the actual question\"}],\"padding\":\"" + "x".repeat(32 * 1024) + "\"}";

        var reference = store.offload("trace-1", "span-1", "in", body);

        assertTrue(reference.contains("the actual question"));
        assertEquals("the actual question", store.preview(reference));
        assertEquals("the actual question", TracePreviewExtractor.extract(reference));
    }

    @Test
    void outputPreviewIsTruncatedHead() {
        var body = "  tool result line one\n line two " + "y".repeat(32 * 1024);

        var reference = store.offload("trace-1", "span-1", "out", body);

        var preview = store.preview(reference);
        assertTrue(preview.startsWith("tool result line one line two"));
        assertTrue(preview.length() <= 512);
    }

    @Test
    void deletesOnlyClosedMonthBodies() {
        var current = YearMonth.now(ZoneOffset.UTC).toString();
        var currentRef = ref("dev-ai/trace-bodies/" + current + "/trace-1/span-1.in.json.gz");
        var oldRef = ref("dev-ai/trace-bodies/2026-01/trace-1/span-1.in.json.gz");

        store.deleteObjects(List.of(currentRef, oldRef, "not a reference"));

        verify(storage, never()).deleteObject("trace-container", "dev-ai/trace-bodies/" + current + "/trace-1/span-1.in.json.gz");
        verify(storage).deleteObject("trace-container", "dev-ai/trace-bodies/2026-01/trace-1/span-1.in.json.gz");
    }

    @Test
    void keepsBodyInlineWhenUploadFails() {
        var failing = mock(ObjectStorageService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("storage down"))
            .when(failing).uploadObject(eq("trace-container"), any(String.class), any(byte[].class), eq("application/json"));
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(failing);
        store.objectStorageResolver = resolver;

        var body = "x".repeat(64 * 1024);
        assertEquals(body, store.offload("trace-1", "span-1", "in", body));
    }

    @Test
    void leavesNonReferenceValuesUntouched() {
        var body = "{\"normal\":\"payload\"}";
        assertFalse(store.isRef(body));
        assertNull(store.preview(body));
        assertEquals(body, store.hydrate(body, true, Long.MAX_VALUE));

        var foreignRef = "{\"$coreai_blob_ref\":\"https://example.com/not-ours\"}";
        assertFalse(store.isRef(foreignRef));
        assertEquals(foreignRef, store.hydrate(foreignRef, true, Long.MAX_VALUE));
    }

    @Test
    void disabledModeKeepsEverythingInline() {
        store.configure("false", 16 * 1024, "trace-container", "dev-ai", true);
        var body = "x".repeat(64 * 1024);

        assertEquals(body, store.offload("trace-1", "span-1", "in", body));
        assertTrue(objects.isEmpty());
    }

    @Test
    void sameContentMatchesInlineAndOffloadedReferenceBySha256() {
        var body = "x".repeat(64 * 1024);
        var reference = store.offload("trace-1", null, "in", body);

        assertTrue(TraceBodyStore.sameContent("hello", "hello"));
        assertFalse(TraceBodyStore.sameContent("hello", "world"));
        assertFalse(TraceBodyStore.sameContent(null, "hello"));
        assertTrue(TraceBodyStore.sameContent(reference, body));
        assertFalse(TraceBodyStore.sameContent(reference, body + "!"));
        assertFalse(TraceBodyStore.sameContent(reference, "y".repeat(64 * 1024)));
        assertFalse(TraceBodyStore.sameContent(ref("dev-ai/trace-bodies/2026-01/trace-1/span-1.in.json.gz"), body));
    }

    private String ref(String key) {
        Map<String, Object> reference = new HashMap<>();
        reference.put("$coreai_blob_ref", "coreai-blob://" + key);
        return JsonUtil.toJson(reference);
    }

    private ObjectStorageService storage() {
        var service = mock(ObjectStorageService.class);
        try {
            org.mockito.Mockito.doAnswer(invocation -> {
                objects.put(invocation.getArgument(1), invocation.getArgument(2));
                return null;
            }).when(service).uploadObject(any(String.class), any(String.class), any(byte[].class), any(String.class));
            when(service.downloadObject(any(String.class), any(String.class))).thenAnswer(invocation -> objects.get(invocation.getArgument(1)));
            org.mockito.Mockito.doAnswer(invocation -> {
                objects.remove(invocation.getArgument(1));
                return null;
            }).when(service).deleteObject(any(String.class), any(String.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return service;
    }
}
