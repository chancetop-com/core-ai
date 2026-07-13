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
import java.util.stream.Collectors;
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

    private static long getLong(org.bson.Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.longValue() : 0L;
    }

    private static double getDouble(org.bson.Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    private static double getPercentile(org.bson.Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number num) {
            return num.doubleValue();
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    static double computeP90(List<Object> values) {
        if (values == null || values.isEmpty()) return 0.0;
        var nums = values.stream()
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .sorted()
            .toArray();
        if (nums.length == 0) return 0.0;
        // P90 = value at ceil(0.90 * n) position (1-indexed), i.e. index ceil(0.90*n) - 1
        int idx = (int) Math.ceil(0.90 * nums.length) - 1;
        return nums[Math.max(idx, 0)];
    }

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

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * Aggregate stats for a specific date and upsert into trace_daily_stats.
     * Idempotent — safe to re-run.
     *
     * @return number of user-day records written
     */
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
                    .append("agent_id", new org.bson.Document("$ifNull", java.util.List.of("$agent_id", NO_AGENT)))
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
            long inputTokens = getLong(row, "input_tokens");
            long outputTokens = getLong(row, "output_tokens");
            long totalTokens = getLong(row, "total_tokens");
            long cachedTokens = getLong(row, "cached_tokens");
            double costUsd = getDouble(row, "cost_usd");
            long callCount = getLong(row, "call_count");
            double avgTotalTokens = getDouble(row, "avg_total_tokens");
            double avgCostUsd = getDouble(row, "avg_cost_usd");
            if (totalTokens <= 0) totalTokens = inputTokens + outputTokens;

            @SuppressWarnings("unchecked")
            var allTotalTokens = (List<Object>) row.get("all_total_tokens");
            @SuppressWarnings("unchecked")
            var allCostUsd = (List<Object>) row.get("all_cost_usd");
            @SuppressWarnings("unchecked")
            var sessionIds = (List<String>) row.get("session_ids");
            double p90TotalTokens = computeP90(allTotalTokens);
            double p90CostUsd = computeP90(allCostUsd);
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

    /**
     * Archive all traces with {@code started_at < cutoff} and their spans to
     * object storage as NDJSON (gzipped), then delete them from MongoDB.
     *
     * <p>Each line is a JSON object with a {@code _type} field ({@code "trace"}
     * or {@code "span"}) so archived data can be distinguished on restore.</p>
     *
     * <p>Safety check: skips archive if trace_daily_stats does not cover the
     * cutoff date (stats must exist before raw data is deleted).</p>
     *
     * <p>Processes traces in batches ({@value #ARCHIVE_BATCH_SIZE} at a time)
     * to keep memory bounded regardless of total trace count.</p>
     *
     * @param cutoff traces strictly before this instant are archived
     * @return number of traces archived and deleted, or -1 if skipped
     */
    @SuppressWarnings({"checkstyle:ExecutableStatementCount", "checkstyle:MethodLength"})
    public int archiveTraces(ZonedDateTime cutoff) {
        if (storageService == null || archiveContainer == null) {
            LOGGER.warn("archive skipped: object storage not configured, cutoff={}", cutoff);
            return -1;
        }

        LocalDate cutoffDate = cutoff.toLocalDate();

        // Safety: verify stats cover the cutoff date before deleting raw data
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
        List<String> allTraceIds = new ArrayList<>();
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

                var traceIds = extractTraceIds(batch);
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
                        if (trace.traceId != null) allTraceIds.add(trace.traceId);
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
                    var subTraceIds = extractTraceIds(subBatch);
                    var spansByTraceId = loadSpansForTraces(subTraceIds);

                    int spanCount = writeAndUploadPart(blobPrefix, part, subBatch, spansByTraceId);
                    totalSpanCount += spanCount;

                    for (var trace : subBatch) {
                        if (trace.traceId != null) allTraceIds.add(trace.traceId);
                    }
                    spansByTraceId.clear();

                    LOGGER.info("archive part {}: {} traces + {} spans uploaded",
                            part, subBatch.size(), spanCount);
                    offset += subBatch.size();
                    part++;
                    start = end;
                }
                batch.clear();
            }

            // All parts uploaded successfully — safe to delete from MongoDB
            deleteSpansBatched(allTraceIds);
            traceCollection.delete(Filters.lt("started_at", cutoff));
            LOGGER.info("archived {} traces + {} spans in {} parts to {}, cutoff={}",
                    totalCount, totalSpanCount, part - 1, blobPrefix, cutoff);
        } catch (IOException e) {
            throw new RuntimeException("failed to write archive part", e);
        }
        return totalCount;
    }

    /**
     * Write one batch of traces + spans to a temp file, upload it as a part
     * blob, and clean up the temp file.  Returns the number of spans written.
     */
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

    /**
     * Write one trace + a chunk of its spans to a temp file and upload.
     * Used when a single trace has more spans than {@link #MAX_SPANS_PER_PART}
     * — the trace line is repeated in each chunk-part so each file is
     * self-contained.
     */
    private int uploadSingleTracePart(String blobPrefix, int part, Trace trace,
                                       List<Span> spanChunk) throws IOException {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("traces-archive-part-", ".json.gz");
            try (var gzipOut = new GZIPOutputStream(Files.newOutputStream(tempFile));
                 var writer = new BufferedWriter(new OutputStreamWriter(gzipOut))) {
                writeArchiveLine(writer, "trace", trace);
                for (var span : spanChunk) {
                    writeArchiveLine(writer, "span", span);
                }
            }
            String blobName = String.format("%s/part-%04d.json.gz", blobPrefix, part);
            storageService.uploadObject(archiveContainer, blobName, tempFile);
            return spanChunk.size();
        } finally {
            deleteTempFileQuietly(tempFile);
        }
    }

    private int writeBatchToFile(Path file, List<Trace> batch,
                                  Map<String, List<Span>> spansByTraceId) throws IOException {
        int spanCount = 0;
        try (var gzipOut = new GZIPOutputStream(Files.newOutputStream(file));
             var writer = new BufferedWriter(new OutputStreamWriter(gzipOut))) {
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

    private void deleteSpansBatched(List<String> traceIds) {
        if (traceIds.isEmpty()) return;
        for (int i = 0; i < traceIds.size(); i += ARCHIVE_BATCH_SIZE) {
            int end = Math.min(i + ARCHIVE_BATCH_SIZE, traceIds.size());
            var subList = traceIds.subList(i, end);
            long deleted = spanCollection.delete(Filters.in("trace_id", subList));
            LOGGER.info("deleted {} spans, batch {}-{}/{}", deleted, i, end, traceIds.size());
        }
    }

    private static List<String> extractTraceIds(List<Trace> traces) {
        return traces.stream()
                .map(t -> t.traceId)
                .filter(id -> id != null && !id.isEmpty())
                .toList();
    }

    /**
     * Lightweight aggregation: returns span count per trace_id.
     * Uses a Mongo $group pipeline that only transmits one integer per trace,
     * avoiding the memory cost of loading full Span objects.
     */
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
            int count = doc.getInteger("count", 0);
            if (traceId != null) counts.put(traceId, count);
        }
        return counts;
    }

    /**
     * Load Span objects for a sub-set of trace IDs.  Called per sub-batch
     * so the number of spans loaded at any time stays bounded.
     * <p>Queries are batched ({@value #MAX_TRACE_IDS_PER_SPAN_QUERY} trace IDs
     * at a time) to avoid Mongo socket read timeouts when individual span
     * documents are large.</p>
     */
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
