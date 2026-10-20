package ai.core.server.trace.maintenance;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import ai.core.server.trace.service.TracePayloadStore;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Archives traces older than the retention window to object storage,
 * then deletes the archived traces and their spans from MongoDB.
 *
 * @author stephen
 */
public class TraceArchiveService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceArchiveService.class);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int ARCHIVE_BATCH_SIZE = 50;
    private static final int SPAN_PAGE_SIZE = 10;
    // Azure accepts at most 256MB per single Put Blob. The limit is enforced on the gzip
    // body, so a part is bounded on the uncompressed side: gzip never grows the input
    // materially, keeping every upload well below the 256MB ceiling even for incompressible
    // base64 payloads (which is what trace spans mostly contain).
    private static final long MAX_PART_UNCOMPRESSED_BYTES = 160L * 1024 * 1024;
    private static final long MAX_PART_UPLOAD_BYTES = 256L * 1024 * 1024;
    private static final int SPAN_DELETE_BATCH_SIZE = 30; // delete spans in small batches to avoid Mongo response timeout

    @Inject
    MongoCollection<TraceDailyStats> statsCollection;
    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<Span> spanCollection;
    @Inject
    TracePayloadStore payloadStore;

    private final ObjectStorageServiceResolver objectStorageResolver;
    private final String configuredContainer;
    private final String archivePrefix;
    int retentionDays = 30;
    long maxPartUncompressedBytes = MAX_PART_UNCOMPRESSED_BYTES;

    public TraceArchiveService(ObjectStorageServiceResolver objectStorageResolver, String configuredContainer, String archivePrefix) {
        this.objectStorageResolver = objectStorageResolver;
        this.configuredContainer = configuredContainer;
        this.archivePrefix = archivePrefix;
    }

    private ObjectStorageService storageService() {
        return objectStorageResolver.resolve();
    }

    /**
     * Container the archives are written to: the configured {@code trace.archive.container},
     * or the private artifact container by default. Never a public container - archives hold
     * raw prompts, completions and tool payloads.
     */
    String archiveContainer() {
        if (configuredContainer != null && !configuredContainer.isBlank()) return configuredContainer;
        return objectStorageResolver.artifactContainer();
    }

    public int uploadArchive(ZonedDateTime cutoff) {
        int totalCount = checkArchivePrerequisites(cutoff);
        if (totalCount <= 0) return totalCount;

        LocalDate cutoffDate = cutoff.toLocalDate();
        String blobPrefix = (archivePrefix != null ? archivePrefix + "/" : "")
                + String.format("traces-archive/%s/%s", cutoffDate.format(YEAR_MONTH), cutoffDate);

        var state = new ArchiveState();
        try (var partWriter = new ArchivePartWriter(blobPrefix, state)) {
            int offset = 0;
            while (true) {
                var batchQuery = new Query();
                batchQuery.filter = Filters.lt("started_at", cutoff);
                batchQuery.sort = Sorts.ascending("started_at");
                batchQuery.skip = offset;
                batchQuery.limit = ARCHIVE_BATCH_SIZE;
                var batch = traceCollection.find(batchQuery);
                if (batch.isEmpty()) break;

                for (var trace : batch) {
                    archiveTrace(trace, partWriter);
                }
                offset += batch.size();
                batch.clear();
            }
            partWriter.finish();
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("archive upload failed: " + e.getMessage(), e);
        }

        LOGGER.info("archived {} traces + {} spans in {} parts to {}, cutoff={}",
                totalCount, state.totalSpanCount, state.part - 1, blobPrefix, cutoff);
        return totalCount;
    }

    private int checkArchivePrerequisites(ZonedDateTime cutoff) {
        if (storageService() == null) {
            LOGGER.warn("archive skipped: object storage not configured, cutoff={}", cutoff);
            return -1;
        }

        LocalDate cutoffDate = cutoff.toLocalDate();

        // Safety: verify stats cover the cutoff date before archiving raw data
        long statsCount = countStatsForDate(cutoffDate);
        if (statsCount == 0) {
            LOGGER.warn("archive skipped: no trace_daily_stats for {}, cutoff={}", cutoffDate, cutoff);
            return -1;
        }

        int totalCount = (int) traceCollection.count(Filters.lt("started_at", cutoff));
        if (totalCount == 0) {
            LOGGER.info("no traces to archive, cutoff={}", cutoff);
            return 0;
        }
        return totalCount;
    }

    private void archiveTrace(Trace trace, ArchivePartWriter partWriter) throws IOException {
        if (payloadStore != null) payloadStore.hydrate(trace, true);
        partWriter.writeTrace(trace);
        int offset = 0;
        while (true) {
            var page = loadSpanPage(trace.traceId, offset);
            if (page.isEmpty()) break;
            if (payloadStore != null) payloadStore.hydrate(page, true);
            for (var span : page) {
                partWriter.writeSpan(trace, span);
            }
            offset += page.size();
            page.clear();
        }
    }

    private List<Span> loadSpanPage(String traceId, int offset) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.skip = offset;
        query.limit = SPAN_PAGE_SIZE;
        return spanCollection.find(query);
    }

    public void deleteArchivedTraces(ZonedDateTime cutoff) {
        int offset = 0;
        long totalSpansDeleted = 0;
        var deletedTraceIds = new ArrayList<String>();
        while (true) {
            var batchQuery = new Query();
            batchQuery.filter = Filters.lt("started_at", cutoff);
            batchQuery.sort = Sorts.ascending("_id");
            batchQuery.skip = offset;
            batchQuery.limit = SPAN_DELETE_BATCH_SIZE;
            var batch = traceCollection.find(batchQuery);
            if (batch.isEmpty()) break;

            var traceIds = TraceMaintenanceHelper.extractTraceIds(batch);
            long deleted = spanCollection.delete(Filters.in("trace_id", traceIds));
            totalSpansDeleted += deleted;
            deletedTraceIds.addAll(traceIds);
            offset += batch.size();
        }
        LOGGER.info("deleted {} spans total for traces with started_at < {}", totalSpansDeleted, cutoff);

        long deleted = traceCollection.delete(Filters.lt("started_at", cutoff));
        LOGGER.info("deleted {} traces with started_at < {}", deleted, cutoff);
        if (payloadStore != null) payloadStore.deleteForTraces(deletedTraceIds);
    }

    private byte[] archiveLine(String type, Object value) {
        String json = JsonUtil.toJson(value);
        String line = "{\"_type\":\"" + type + "\"," + json.substring(1) + "\n";
        return line.getBytes(StandardCharsets.UTF_8);
    }

    private void deleteTempFileQuietly(Path tempFile) {
        if (tempFile == null) return;
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            LOGGER.warn("failed to delete temp file: {}", tempFile, e);
        }
    }

    private long countStatsForDate(LocalDate date) {
        return statsCollection.count(Filters.eq("date", date.atStartOfDay(UTC)));
    }

    private static final class ArchiveState {
        int part = 1;
        int totalSpanCount;
    }

    /**
     * Streams one archive part at a time into a temp file and uploads it when the
     * uncompressed size budget is reached. Spans are paged, so the heap never holds
     * more than one page of payloads plus the current gzip buffer.
     */
    private final class ArchivePartWriter implements AutoCloseable {
        private final String blobPrefix;
        private final ArchiveState state;
        private Path tempFile;
        private GZIPOutputStream gzipOut;
        private long rawBytes;
        private int spanCount;
        private int traceCount;
        private String headerTraceId;

        ArchivePartWriter(String blobPrefix, ArchiveState state) throws IOException {
            this.blobPrefix = blobPrefix;
            this.state = state;
            open();
        }

        void writeTrace(Trace trace) throws IOException {
            if (trace.traceId != null && trace.traceId.equals(headerTraceId)) return;
            var line = archiveLine("trace", trace);
            rotateIfNeeded(line.length);
            write(line);
            headerTraceId = trace.traceId;
            traceCount++;
        }

        void writeSpan(Trace trace, Span span) throws IOException {
            var line = archiveLine("span", span);
            rotateIfNeeded(line.length);
            writeTrace(trace);
            write(line);
            spanCount++;
        }

        private void rotateIfNeeded(int nextLineBytes) throws IOException {
            if (rawBytes > 0 && rawBytes + nextLineBytes > maxPartUncompressedBytes) {
                finishPart();
                open();
            }
        }

        private void write(byte[] line) throws IOException {
            gzipOut.write(line);
            rawBytes += line.length;
        }

        void finish() throws IOException {
            if (rawBytes > 0) finishPart();
        }

        private void finishPart() throws IOException {
            closeStreams();
            try {
                long compressedBytes = Files.size(tempFile);
                if (compressedBytes > MAX_PART_UPLOAD_BYTES) {
                    throw new IllegalStateException("archive part " + state.part + " is " + compressedBytes
                            + " bytes, above the object storage upload limit of " + MAX_PART_UPLOAD_BYTES);
                }
                String blobName = String.format("%s/part-%04d.json.gz", blobPrefix, state.part);
                storageService().uploadObject(archiveContainer(), blobName, tempFile);
                LOGGER.info("archive part {} uploaded: {} traces, {} spans, raw={} bytes, gzip={} bytes",
                        state.part, traceCount, spanCount, rawBytes, compressedBytes);
                state.totalSpanCount += spanCount;
                state.part++;
            } finally {
                deleteTempFileQuietly(tempFile);
                tempFile = null;
            }
            rawBytes = 0;
            spanCount = 0;
            traceCount = 0;
            headerTraceId = null;
        }

        private void open() throws IOException {
            tempFile = Files.createTempFile("traces-archive-part-", ".json.gz");
            var fileOut = new BufferedOutputStream(Files.newOutputStream(tempFile), 64 * 1024);
            gzipOut = new GZIPOutputStream(fileOut, 64 * 1024);
        }

        private void closeStreams() throws IOException {
            if (gzipOut != null) {
                gzipOut.close();
                gzipOut = null;
            }
        }

        private void closeStreamsQuietly() {
            try {
                closeStreams();
            } catch (IOException e) {
                LOGGER.warn("failed to close archive part stream: {}", tempFile, e);
            }
        }

        @Override
        public void close() {
            closeStreamsQuietly();
            deleteTempFileQuietly(tempFile);
            tempFile = null;
        }
    }
}
