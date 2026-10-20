package ai.core.server.trace.maintenance;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.utils.JsonUtil;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceArchiveServiceTest {
    @Test
    void archivesLandInThePrivateArtifactContainerByDefault() {
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var service = new TraceArchiveService(resolver, null, null);

        assertEquals("artifacts", service.archiveContainer());
        verify(resolver, never()).multimodalContainer();
    }

    @Test
    void blankConfiguredContainerStillFallsBackToThePrivateArtifactContainer() {
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var service = new TraceArchiveService(resolver, "  ", null);

        assertEquals("artifacts", service.archiveContainer());
    }

    @Test
    void configuredContainerWins() {
        var resolver = mock(ObjectStorageServiceResolver.class);
        var service = new TraceArchiveService(resolver, "custom-archive", null);

        assertEquals("custom-archive", service.archiveContainer());
        verify(resolver, never()).artifactContainer();
    }

    @Test
    void splitsPartsByUncompressedSizeAndStreamsSpansInPages() throws IOException {
        var storage = new CapturingStorage();
        var traces = List.of(trace("trace-1"), trace("trace-2"), trace("trace-3"));
        Map<String, List<Span>> spansByTrace = new LinkedHashMap<>();
        spansByTrace.put("trace-1", spans("trace-1", 12));
        spansByTrace.put("trace-2", spans("trace-2", 3));
        spansByTrace.put("trace-3", List.of());

        var spanQueries = new ArrayList<String>();
        var service = archiveService(storage, traces, spansByTrace, spanQueries);

        int archived = service.uploadArchive(ZonedDateTime.parse("2026-09-20T00:00:00Z"));

        assertEquals(3, archived);
        assertTrue(storage.uploads.size() > 1, "expected the size budget to split the archive into several parts");
        assertTrue(spanQueries.contains("trace-1:0:10"), "first span page limited by page size");
        assertTrue(spanQueries.contains("trace-1:10:10"), "spans are paged instead of loaded all at once");
        assertParts(storage.uploads, service.maxPartUncompressedBytes, traces.size(), 15);
    }

    private TraceArchiveService archiveService(CapturingStorage storage, List<Trace> traces,
                                                      Map<String, List<Span>> spansByTrace, List<String> spanQueries) {
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.artifactContainer()).thenReturn("artifacts");

        var service = new TraceArchiveService(resolver, null, "uat");
        service.maxPartUncompressedBytes = 4096;

        service.statsCollection = mockCollection();
        when(service.statsCollection.count(any(Bson.class))).thenReturn(1L);
        service.traceCollection = mockCollection();
        when(service.traceCollection.count(any(Bson.class))).thenReturn((long) traces.size());
        when(service.traceCollection.find(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return query.skip == 0 ? new ArrayList<>(traces) : List.of();
        });

        service.spanCollection = mockCollection();
        when(service.spanCollection.find(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            String traceId = query.filter.toBsonDocument().getString("trace_id").getValue();
            spanQueries.add(traceId + ":" + query.skip + ":" + query.limit);
            List<Span> all = spansByTrace.get(traceId);
            int from = Math.min(query.skip, all.size());
            int to = Math.min(from + query.limit, all.size());
            return new ArrayList<>(all.subList(from, to));
        });
        return service;
    }

    private void assertParts(List<Upload> uploads, long maxPartBytes, int traceCount, int spanCount) throws IOException {
        Set<String> archivedSpanIds = new HashSet<>();
        Set<String> archivedTraceIds = new HashSet<>();
        for (var upload : uploads) {
            assertEquals("artifacts", upload.container());
            assertTrue(upload.blobName().startsWith("uat/traces-archive/2026-09/2026-09-20/part-"), upload.blobName());

            byte[] raw = gunzip(upload.data());
            assertTrue(raw.length <= maxPartBytes + 2048,
                    "part " + upload.blobName() + " grew to " + raw.length + " uncompressed bytes");

            boolean hasTraceHeader = false;
            for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
                Map<String, Object> row = JsonUtil.toMap(line);
                String type = (String) row.get("_type");
                assertNotNull(type);
                if ("trace".equals(type)) {
                    archivedTraceIds.add((String) row.get("traceId"));
                    hasTraceHeader = true;
                } else {
                    archivedSpanIds.add((String) row.get("id"));
                }
            }
            assertTrue(hasTraceHeader, "part " + upload.blobName() + " has no trace header");
        }

        assertEquals(traceCount, archivedTraceIds.size());
        assertEquals(spanCount, archivedSpanIds.size());
    }

    @SuppressWarnings("unchecked")
    private <T> MongoCollection<T> mockCollection() {
        return mock(MongoCollection.class);
    }

    private Trace trace(String traceId) {
        var trace = new Trace();
        trace.traceId = traceId;
        trace.name = traceId;
        trace.startedAt = ZonedDateTime.parse("2026-09-19T00:00:00Z");
        return trace;
    }

    private List<Span> spans(String traceId, int count) {
        var spans = new ArrayList<Span>(count);
        for (int i = 0; i < count; i++) {
            var span = new Span();
            span.id = traceId + "-span-" + i;
            span.traceId = traceId;
            span.spanId = traceId + "-" + i;
            span.name = "span-" + i;
            span.input = "x".repeat(1000);
            span.startedAt = ZonedDateTime.parse("2026-09-19T00:00:00Z");
            spans.add(span);
        }
        return spans;
    }

    private byte[] gunzip(byte[] data) throws IOException {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    private static final class Upload {
        private final String container;
        private final String blobName;
        private final byte[] data;

        private Upload(String container, String blobName, byte[] data) {
            this.container = container;
            this.blobName = blobName;
            this.data = data.clone();
        }

        private String blobName() {
            return blobName;
        }

        private String container() {
            return container;
        }

        private byte[] data() {
            return data.clone();
        }
    }

    private static final class CapturingStorage implements ObjectStorageService {
        private final List<Upload> uploads = new ArrayList<>();

        @Override
        public UploadCredential generateUploadCredential(String container, String blobName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DownloadCredential generateDownloadCredential(String container, String blobName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] downloadObject(String container, String blobName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void uploadObject(String container, String blobName, Path file) {
            uploadObject(container, blobName, file, null);
        }

        @Override
        public void uploadObject(String container, String blobName, Path file, String contentType) {
            try {
                uploads.add(new Upload(container, blobName, Files.readAllBytes(file)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void uploadObject(String container, String blobName, byte[] data, String contentType) {
            uploads.add(new Upload(container, blobName, data));
        }

        @Override
        public void downloadObjectToFile(String container, String blobName, Path target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectMetadata headObject(String container, String blobName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String container, String blobName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteObject(String container, String blobName) {
            throw new UnsupportedOperationException();
        }
    }
}
