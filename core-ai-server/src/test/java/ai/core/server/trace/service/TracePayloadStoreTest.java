package ai.core.server.trace.service;

import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.TracePayloadBlob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
