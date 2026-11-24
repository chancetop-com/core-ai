package ai.core.server.trace.service;

import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.TracePayloadBlob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author core-ai
 */
class TracePayloadStoreBudgetTest {
    private static String dataUrl(String fill) {
        // ~1.2 MB of base64 each: one fits the 2 MB detail budget, two do not
        return "data:image/png;base64," + fill.repeat(1_200_000);
    }

    private static int occurrences(String value, String needle) {
        var count = 0;
        var index = value.indexOf(needle);
        while (index >= 0) {
            count++;
            index = value.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private final InMemoryRepository repository = new InMemoryRepository();
    private final TracePayloadStore store = new TracePayloadStore();

    @BeforeEach
    void setUp() {
        repository.blobs.clear();
        repository.failStore = false;
        store.payloadRepository = repository;
    }

    @Test
    void replacesAnImageThatCannotBeStoredWithAPlaceholder() {
        repository.failStore = true;
        var dataUrl = "data:image/png;base64," + "A".repeat(64);

        var compacted = store.newCompactor().compact("trace-1", "{\"url\":\"" + dataUrl + "\"}");

        assertFalse(compacted.contains("data:image/"));
        assertTrue(compacted.contains("[image omitted from trace: 48 bytes]"));
    }

    @Test
    void storesAndServesAnUploadedPayload() {
        var dataUrl = "data:image/png;base64," + "AQID";

        var hash = store.storePayload("trace-1", dataUrl);

        assertNotNull(hash);
        var payload = store.payload("trace-1", hash);
        assertNotNull(payload);
        assertEquals(3, payload.bytes().length);
        assertEquals("image/png", payload.contentType());
        assertNull(store.payload("trace-2", hash));
    }

    @Test
    void capsTheBytesADetailResponseHydrates() {
        var first = store.storePayload("trace-1", dataUrl("A"));
        var second = store.storePayload("trace-1", dataUrl("B"));
        var span = new Span();
        span.traceId = "trace-1";
        span.input = "{\"a\":\"coreai-image://" + first + "\",\"b\":\"coreai-image://" + second + "\"}";

        store.hydrate(span, false);

        assertEquals(1, occurrences(span.input, "data:image/"));
        assertEquals(1, occurrences(span.input, "coreai-image://"));
    }

    private static final class InMemoryRepository implements TracePayloadRepository {
        private final List<TracePayloadBlob> blobs = new ArrayList<>();
        private boolean failStore;

        @Override
        public boolean store(TracePayloadBlob blob) {
            if (failStore) return false;
            blobs.add(blob);
            return true;
        }

        @Override
        public List<TracePayloadBlob> findByTrace(String traceId, List<String> blobHashes) {
            var found = new ArrayList<TracePayloadBlob>();
            for (var blob : blobs) {
                if (!blob.traceId.equals(traceId)) continue;
                for (var hash : blobHashes) {
                    if (blob.id.equals(traceId + ":" + hash)) found.add(blob);
                }
            }
            return found;
        }

        @Override
        public long deleteByTraceIds(List<String> traceIds) {
            return 0;
        }
    }
}
