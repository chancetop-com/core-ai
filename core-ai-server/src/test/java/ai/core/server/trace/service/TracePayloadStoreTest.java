package ai.core.server.trace.service;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.TracePayloadBlob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracePayloadStoreTest {
    private final Map<String, TracePayloadBlob> blobs = new HashMap<>();
    private final TracePayloadRepository repository = repository();
    private TracePayloadStore store;

    @BeforeEach
    void setUp() {
        blobs.clear();
        store = new TracePayloadStore();
        store.payloadRepository = repository;
    }

    @Test
    void compactsRepeatedImageAndRestoresExactPayload() {
        var dataUrl = "data:image/png;base64," + "A".repeat(64);
        var payload = "{\"a\":\"" + dataUrl + "\",\"b\":\"" + dataUrl + "\",\"text\":\"hello\"}";

        var compacted = store.newCompactor().compact("trace-1", payload);

        assertFalse(compacted.contains("data:image/"));
        assertEquals(2, occurrences(compacted));
        assertEquals(1, blobs.size());
        verify(repository, times(1)).store(any(TracePayloadBlob.class));

        var blob = blobs.values().iterator().next();
        assertEquals("trace-1", blob.traceId);
        assertEquals("image/png", blob.contentType);
        assertEquals(48, blob.sizeBytes);

        var span = new Span();
        span.traceId = "trace-1";
        span.input = compacted;
        store.hydrate(span, true);
        assertEquals(payload, span.input);
    }

    @Test
    void keepsTheSameImageSeparatePerTrace() {
        var dataUrl = "data:image/png;base64," + "A".repeat(64);
        var payload = "{\"image\":\"" + dataUrl + "\"}";
        var compactor = store.newCompactor();

        var first = compactor.compact("trace-1", payload);
        var second = compactor.compact("trace-2", payload);

        assertEquals(first, second);
        assertEquals(2, blobs.size());
        verify(repository, times(2)).store(any(TracePayloadBlob.class));
    }

    @Test
    void reusesOneRowWhenTheSameTraceRepeatsTheImage() {
        var dataUrl = "data:image/png;base64," + "A".repeat(64);
        var payload = "{\"image\":\"" + dataUrl + "\"}";
        var compactor = store.newCompactor();

        compactor.compact("trace-1", payload);
        compactor.compact("trace-1", payload);

        assertEquals(1, blobs.size());
        verify(repository, times(1)).store(any(TracePayloadBlob.class));
    }

    @Test
    void leavesDataUrlInlineWhenItIsNotACompleteValue() {
        var payload = "{\"image\":\"data:image/png;base64,AAAA,trailing\"}";

        assertEquals(payload, store.newCompactor().compact("trace-1", payload));
        assertTrue(blobs.isEmpty());
    }

    @Test
    void missingBlobFailsStrictHydration() {
        var dataUrl = "data:image/png;base64," + "A".repeat(64);
        var compacted = store.newCompactor().compact("trace-1", "{\"image\":\"" + dataUrl + "\"}");
        blobs.clear();

        var span = new Span();
        span.traceId = "trace-1";
        span.input = compacted;

        assertThrows(IllegalStateException.class, () -> store.hydrate(span, true));
    }

    @Test
    void missingBlobKeepsReferenceWhenNotStrict() {
        var dataUrl = "data:image/png;base64," + "A".repeat(64);
        var compacted = store.newCompactor().compact("trace-1", "{\"image\":\"" + dataUrl + "\"}");
        blobs.clear();

        var span = new Span();
        span.traceId = "trace-1";
        span.input = compacted;
        store.hydrate(span, false);

        assertEquals(compacted, span.input);
    }

    @Test
    void storesObjectReferenceAndServesBytesFromObjectStorage() {
        var objectStorage = mock(ObjectStorageService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(objectStorage);
        store.objectStorageResolver = resolver;
        store.configure("object", "trace-container", "dev", true);

        var dataUrl = "data:image/png;base64," + "A".repeat(64);
        var compacted = store.newCompactor().compact("trace-1", "{\"image\":\"" + dataUrl + "\"}");
        var expectedBytes = java.util.Base64.getDecoder().decode("A".repeat(64));

        var keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).uploadObject(eq("trace-container"), keyCaptor.capture(),
            argThat((byte[] bytes) -> Arrays.equals(bytes, expectedBytes)), eq("image/png"));
        var stored = blobs.values().iterator().next();
        assertNull(stored.data);
        assertTrue(stored.storageKey.startsWith("dev/trace-blobs/"));
        assertEquals(stored.storageKey, keyCaptor.getValue());
        assertFalse(compacted.contains("data:image/"));

        when(objectStorage.downloadObject("trace-container", stored.storageKey)).thenReturn(expectedBytes);
        var payload = store.payload("trace-1", stored.id.substring("trace-1:".length()));
        assertEquals(expectedBytes.length, payload.bytes().length);
        assertEquals("image/png", payload.contentType());
    }

    @Test
    void deletesOnlyUnreferencedObjects() {
        var objectStorage = mock(ObjectStorageService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(objectStorage);
        store.objectStorageResolver = resolver;
        store.configure("object", "trace-container", "dev", true);
        when(repository.deleteByTraceIdsReturningStorageKeys(List.of("trace-1")))
            .thenReturn(List.of("key-unreferenced", "key-shared"));
        when(repository.hasStorageKey("key-shared")).thenReturn(true);

        var keys = store.deleteForTraces(List.of("trace-1"));
        store.deleteObjects(keys);

        verify(objectStorage).deleteObject("trace-container", "key-unreferenced");
        verify(objectStorage, times(0)).deleteObject("trace-container", "key-shared");
    }

    @Test
    void backfillsLegacyInlineRowsToObjectStorage() {
        var objectStorage = mock(ObjectStorageService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(objectStorage);
        store.objectStorageResolver = resolver;
        store.configure("object", "trace-container", "dev", true);
        store.configureBackfill(true, 500);

        var dataUrl = "data:image/png;base64," + "A".repeat(64);
        var legacy = new TracePayloadBlob();
        legacy.traceId = "trace-1";
        legacy.id = "trace-1:" + "b".repeat(64);
        legacy.data = dataUrl;
        when(repository.findBackfillCandidates(500)).thenReturn(List.of(legacy));
        when(repository.updateBackfillReference(legacy)).thenReturn(1);

        assertEquals(1, store.backfillBatch(0));
        assertNull(legacy.data);
        assertTrue(legacy.storageKey.startsWith("dev/trace-blobs/"));
        assertEquals(48L, legacy.sizeBytes);
        verify(objectStorage).uploadObject(eq("trace-container"), eq(legacy.storageKey),
            argThat((byte[] bytes) -> bytes.length == 48), eq("image/png"));
        verify(repository).updateBackfillReference(legacy);
    }

    @Test
    void propagatesStorageFailureInsteadOfKeepingAPlaceholder() {
        when(repository.store(any(TracePayloadBlob.class))).thenThrow(new IllegalStateException("db down"));
        var payload = "{\"image\":\"data:image/png;base64," + "A".repeat(64) + "\"}";

        assertThrows(IllegalStateException.class, () -> store.newCompactor().compact("trace-1", payload));
    }

    private int occurrences(String value) {
        int count = 0;
        int index = value.indexOf(TracePayloadStore.REF_PREFIX);
        while (index >= 0) {
            count++;
            index = value.indexOf(TracePayloadStore.REF_PREFIX, index + TracePayloadStore.REF_PREFIX.length());
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private TracePayloadRepository repository() {
        var repository = mock(TracePayloadRepository.class);
        when(repository.store(any(TracePayloadBlob.class))).thenAnswer(invocation -> {
            var blob = invocation.getArgument(0, TracePayloadBlob.class);
            blobs.putIfAbsent(blob.id, blob);
            return Boolean.TRUE;
        });
        when(repository.findByTrace(any(), any())).thenAnswer(invocation -> {
            String traceId = invocation.getArgument(0);
            var hashes = invocation.getArgument(1, java.util.List.class);
            var result = new ArrayList<TracePayloadBlob>();
            for (var hash : hashes) {
                var blob = blobs.get(traceId + ':' + hash);
                if (blob != null) result.add(blob);
            }
            return result;
        });
        return repository;
    }
}
