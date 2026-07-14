package ai.core.server.trace.maintenance;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Daily trace maintenance: aggregates per-user per-agent token/cost stats from
 * the traces collection into trace_daily_stats for fast Dashboard queries,
 * and archives traces older than the retention window to object storage.
 *
 * @author cyril
 */
public class TraceDailyMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceDailyMaintenanceService.class);
    private static final String NO_AGENT = "(no agent)";
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int ARCHIVE_BATCH_SIZE = 300;
    // Azure single PUT Blob limit is 256MB. With typical ~50KB serialized span size,
    // 2000 spans produce ~100MB. Even with outliers (200KB/span) we stay under 256MB.
    private static final int MAX_SPANS_PER_PART = 2000;
    private static final long MAX_PART_SIZE_BYTES = 200 * 1024 * 1024; // 200 MB safety threshold
    private static final int MAX_TRACE_IDS_PER_SPAN_QUERY = 50; // batch span queries to avoid Mongo socket read timeout
    private static final int SPAN_DELETE_BATCH_SIZE = 30; // delete spans in small batches to avoid Mongo response timeout

    @Inject
    MongoCollection<TraceDailyStats> statsCollection;

    @Inject
    MongoCollection<Trace> traceCollection;

    @Inject
    MongoCollection<Span> spanCollection;

    // Archive configuration — set by ServerModule after object storage is initialized
    ObjectStorageService storageService;
    String archiveContainer;
    String archivePrefix;
    int retentionDays = 30;

    public void setStorageService(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    public void setArchiveContainer(String archiveContainer) {
        this.archiveContainer = archiveContainer;
    }

    public void setArchivePrefix(String archivePrefix) {
        this.archivePrefix = archivePrefix;
    }

    public int aggregateDailyStats(LocalDate date) {
        ZonedDateTime dayStart = date.atStartOfDay(UTC);
        ZonedDateTime dayEnd = date.plusDays(1).atStartOfDay(UTC);
        List<org.bson.Document> rows = aggregateTraces(dayStart, dayEnd, date);
        return upsertStats(rows, date);
    }

    private List<org.bson.Document> aggregateTraces(ZonedDateTime dayStart, ZonedDateTime dayEnd, LocalDate date) {
        var aggregate = new Aggregate<org.bson.Document>();
        aggregate.resultClass = org.bson.Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(
                Filters.gte("started_at", dayStart),
                Filters.lt("started_at", dayEnd)
            )),
            Aggregates.group(
                new org.bson.Document()
                    .append("user_id", "$user_id")
                    .append("agent_id", new org.bson.Document("$ifNull", List.of("$agent_id", NO_AGENT)))
                    .append("date", date.toString()),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("call_count", 1L),
                Accumulators.avg("avg_total_tokens", "$total_tokens"),
                Accumulators.avg("avg_cost_usd", "$cost_usd"),
                // Push token/cost values for P90 computation in Java
                Accumulators.push("all_total_tokens", "$total_tokens"),
                Accumulators.push("all_cost_usd", "$cost_usd"),
                Accumulators.addToSet("session_ids", "$session_id")
            )
        );
        return traceCollection.aggregate(aggregate);
    }

    @SuppressWarnings({"checkstyle:ExecutableStatementCount", "checkstyle:MethodLength"})
    private int upsertStats(List<org.bson.Document> rows, LocalDate date) {
        int created = 0;
        int replaced = 0;

        for (var row : rows) {
            var id = row.get("_id", org.bson.Document.class);
            if (id == null) continue;
            String userId = id.getString("user_id");
            String agentId = id.getString("agent_id");
            if (userId == null) continue;
            if (agentId == null) agentId = NO_AGENT;

            String docId = userId + "_" + agentId + "_" + date;
            long inputTokens = TraceMaintenanceHelper.getLong(row, "input_tokens");
            long outputTokens = TraceMaintenanceHelper.getLong(row, "output_tokens");
            long totalTokens = TraceMaintenanceHelper.getLong(row, "total_tokens");
            long cachedTokens = TraceMaintenanceHelper.getLong(row, "cached_tokens");
            double costUsd = TraceMaintenanceHelper.getDouble(row, "cost_usd");
            long callCount = TraceMaintenanceHelper.getLong(row, "call_count");
            double avgTotalTokens = TraceMaintenanceHelper.getDouble(row, "avg_total_tokens");
            double avgCostUsd = TraceMaintenanceHelper.getDouble(row, "avg_cost_usd");
            if (totalTokens <= 0) totalTokens = inputTokens + outputTokens;

            @SuppressWarnings("unchecked")
            var allTotalTokens = (List<Object>) row.get("all_total_tokens");
            @SuppressWarnings("unchecked")
            var allCostUsd = (List<Object>) row.get("all_cost_usd");
            @SuppressWarnings("unchecked")
            var sessionIds = (List<String>) row.get("session_ids");
            double p90TotalTokens = TraceMaintenanceHelper.computeP90(allTotalTokens);
            double p90CostUsd = TraceMaintenanceHelper.computeP90(allCostUsd);
            long sessionCount = sessionIds != null ? sessionIds.size() : 0;

            var existing = statsCollection.get(docId);
            if (existing.isPresent()) {
                var stats = existing.get();
                stats.agentId = agentId;
                stats.date = date.atStartOfDay(UTC);
                stats.inputTokens = inputTokens;
                stats.outputTokens = outputTokens;
                stats.totalTokens = totalTokens;
                stats.cachedTokens = cachedTokens;
                stats.costUsd = costUsd;
                stats.callCount = callCount;
                stats.avgTotalTokens = avgTotalTokens;
                stats.avgCostUsd = avgCostUsd;
                stats.p90TotalTokens = p90TotalTokens;
                stats.p90CostUsd = p90CostUsd;
                stats.sessionCount = sessionCount;
                statsCollection.replace(stats);
                replaced++;
            } else {
                var stats = new TraceDailyStats();
                stats.id = docId;
                stats.userId = userId;
                stats.agentId = agentId;
                stats.date = date.atStartOfDay(UTC);
                stats.inputTokens = inputTokens;
                stats.outputTokens = outputTokens;
                stats.totalTokens = totalTokens;
                stats.cachedTokens = cachedTokens;
                stats.costUsd = costUsd;
                stats.callCount = callCount;
                stats.avgTotalTokens = avgTotalTokens;
                stats.avgCostUsd = avgCostUsd;
                stats.p90TotalTokens = p90TotalTokens;
                stats.p90CostUsd = p90CostUsd;
                stats.sessionCount = sessionCount;
                statsCollection.insert(stats);
                created++;
            }
        }

        LOGGER.info("daily stats aggregated, date={}, total={}, created={}, replaced={}",
                date, created + replaced, created, replaced);
        return created + replaced;
    }

    // --- Archive ---
    @SuppressWarnings({"checkstyle:ExecutableStatementCount", "checkstyle:MethodLength"})
    public int uploadArchive(ZonedDateTime cutoff) {
        if (storageService == null || archiveContainer == null) {
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

        String blobPrefix = (archivePrefix != null ? archivePrefix + "/" : "")
                + String.format("traces-archive/%s/%s", cutoffDate.format(YEAR_MONTH), cutoffDate);
        int totalSpanCount = 0;
        int part = 1;
        int offset = 0;

        try {
            while (true) {
                var batchQuery = new Query();
                batchQuery.filter = Filters.lt("started_at", cutoff);
                batchQuery.sort = Sorts.ascending("started_at");
                batchQuery.skip = offset;
                batchQuery.limit = ARCHIVE_BATCH_SIZE;
                var batch = traceCollection.find(batchQuery);
                if (batch.isEmpty()) break;

                var traceIds = TraceMaintenanceHelper.extractTraceIds(batch);
                var spanCounts = getSpanCountsByTraceId(traceIds);

                // Process traces in span-bounded sub-batches to keep per-part
                // memory under control even for span-heavy traces
                int start = 0;
                while (start < batch.size()) {
                    int sc = spanCounts.getOrDefault(batch.get(start).traceId, 0);

                    if (sc > MAX_SPANS_PER_PART) {
                        // Single trace with excessive spans — split across multiple parts
                        Trace trace = batch.get(start);
                        var allSpans = loadSpansForTraces(List.of(trace.traceId))
                                .getOrDefault(trace.traceId, List.of());
                        int chunks = (allSpans.size() + MAX_SPANS_PER_PART - 1) / MAX_SPANS_PER_PART;
                        for (int c = 0; c < allSpans.size(); c += MAX_SPANS_PER_PART) {
                            int chunkEnd = Math.min(c + MAX_SPANS_PER_PART, allSpans.size());
                            int chunkSpanCount = uploadSingleTracePart(blobPrefix, part, trace,
                                    allSpans.subList(c, chunkEnd));
                            totalSpanCount += chunkSpanCount;
                            LOGGER.info("archive part {}: 1 trace + {} spans uploaded (oversized {}/{})",
                                    part, chunkSpanCount, c / MAX_SPANS_PER_PART + 1, chunks);
                            part++;
                        }
                        allSpans.clear();
                        offset++;
                        start++;
                        continue;
                    }

                    int end = start;
                    int accumulatedSpans = 0;
                    while (end < batch.size()) {
                        int sc2 = spanCounts.getOrDefault(batch.get(end).traceId, 0);
                        if (sc2 > MAX_SPANS_PER_PART) break; // handled in next outer iteration
                        if (accumulatedSpans > 0 && accumulatedSpans + sc2 > MAX_SPANS_PER_PART) break;
                        accumulatedSpans += sc2;
                        end++;
                    }

                    var subBatch = new ArrayList<>(batch.subList(start, end));
                    var subTraceIds = TraceMaintenanceHelper.extractTraceIds(subBatch);
                    var spansByTraceId = loadSpansForTraces(subTraceIds);

                    int spanCount = writeAndUploadPart(blobPrefix, part, subBatch, spansByTraceId);
                    totalSpanCount += spanCount;

                    spansByTraceId.clear();

                    LOGGER.info("archive part {}: {} traces + {} spans uploaded",
                            part, subBatch.size(), spanCount);
                    offset += subBatch.size();
                    part++;
                    start = end;
                }
                batch.clear();
            }

            LOGGER.info("archived {} traces + {} spans in {} parts to {}, cutoff={}",
                    totalCount, totalSpanCount, part - 1, blobPrefix, cutoff);
        } catch (Exception e) {
            throw new RuntimeException("archive upload failed: " + e.getMessage(), e);
        }
        return totalCount;
    }

    public void deleteArchivedTraces(ZonedDateTime cutoff) {
        int offset = 0;
        long totalSpansDeleted = 0;
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
            offset += batch.size();
        }
        LOGGER.info("deleted {} spans total for traces with started_at < {}", totalSpansDeleted, cutoff);

        long deleted = traceCollection.delete(Filters.lt("started_at", cutoff));
        LOGGER.info("deleted {} traces with started_at < {}", deleted, cutoff);
    }

    private int writeAndUploadPart(String blobPrefix, int part, List<Trace> batch,
                                    Map<String, List<Span>> spansByTraceId) throws IOException {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("traces-archive-part-", ".json.gz");
            int spanCount = writeBatchToFile(tempFile, batch, spansByTraceId);
            long fileSize = Files.size(tempFile);
            if (fileSize > MAX_PART_SIZE_BYTES) {
                LOGGER.warn("archive part {} exceeds {} bytes (actual={}), upload may fail with 413",
                        part, MAX_PART_SIZE_BYTES, fileSize);
            }
            String blobName = String.format("%s/part-%04d.json.gz", blobPrefix, part);
            storageService.uploadObject(archiveContainer, blobName, tempFile);
            return spanCount;
        } finally {
            deleteTempFileQuietly(tempFile);
        }
    }

    private int uploadSingleTracePart(String blobPrefix, int part, Trace trace,
                                       List<Span> spanChunk) throws IOException {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("traces-archive-part-", ".json.gz");
            writePartToTempFile(tempFile, trace, spanChunk);
            String blobName = String.format("%s/part-%04d.json.gz", blobPrefix, part);
            storageService.uploadObject(archiveContainer, blobName, tempFile);
            return spanChunk.size();
        } finally {
            deleteTempFileQuietly(tempFile);
        }
    }

    private void writePartToTempFile(Path tempFile, Trace trace, List<Span> spanChunk) throws IOException {
        try (var gzipOut = new GZIPOutputStream(Files.newOutputStream(tempFile));
             var writer = new BufferedWriter(new OutputStreamWriter(gzipOut, StandardCharsets.UTF_8))) {
            writeArchiveLine(writer, "trace", trace);
            for (var span : spanChunk) {
                writeArchiveLine(writer, "span", span);
            }
        }
    }

    private int writeBatchToFile(Path file, List<Trace> batch,
                                  Map<String, List<Span>> spansByTraceId) throws IOException {
        int spanCount = 0;
        try (var gzipOut = new GZIPOutputStream(Files.newOutputStream(file));
             var writer = new BufferedWriter(new OutputStreamWriter(gzipOut, StandardCharsets.UTF_8))) {
            for (var trace : batch) {
                writeArchiveLine(writer, "trace", trace);
                var spans = spansByTraceId.getOrDefault(trace.traceId, List.of());
                for (var span : spans) {
                    writeArchiveLine(writer, "span", span);
                }
                spanCount += spans.size();
            }
        }
        return spanCount;
    }

    private void writeArchiveLine(BufferedWriter writer, String type, Object obj) throws IOException {
        String json = JsonUtil.toJson(obj);
        writer.write("{\"_type\":\"");
        writer.write(type);
        writer.write("\",");
        writer.write(json, 1, json.length() - 1);
        writer.newLine();
    }

    private Map<String, Integer> getSpanCountsByTraceId(List<String> traceIds) {
        if (traceIds.isEmpty()) return Map.of();
        var aggregate = new Aggregate<org.bson.Document>();
        aggregate.resultClass = org.bson.Document.class;
        aggregate.pipeline = List.of(
                Aggregates.match(Filters.in("trace_id", traceIds)),
                Aggregates.group("$trace_id", Accumulators.sum("count", 1))
        );
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var doc : spanCollection.aggregate(aggregate)) {
            String traceId = doc.getString("_id");
            if (traceId != null) {
                int count = doc.getInteger("count", 0);
                counts.put(traceId, count);
            }
        }
        return counts;
    }

    private Map<String, List<Span>> loadSpansForTraces(List<String> traceIds) {
        if (traceIds.isEmpty()) return Map.of();
        Map<String, List<Span>> result = new LinkedHashMap<>();
        for (int i = 0; i < traceIds.size(); i += MAX_TRACE_IDS_PER_SPAN_QUERY) {
            int end = Math.min(i + MAX_TRACE_IDS_PER_SPAN_QUERY, traceIds.size());
            var subIds = traceIds.subList(i, end);
            var spanQuery = new Query();
            spanQuery.filter = Filters.in("trace_id", subIds);
            spanCollection.find(spanQuery).stream()
                    .filter(s -> s.traceId != null)
                    .forEach(s -> result.computeIfAbsent(s.traceId, k -> new ArrayList<>()).add(s));
        }
        return result;
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
}
