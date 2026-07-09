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
    private static final int ARCHIVE_BATCH_SIZE = 1000;

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
    int retentionDays = 30;

    public void setStorageService(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    public void setArchiveContainer(String archiveContainer) {
        this.archiveContainer = archiveContainer;
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

        String blobName = String.format("traces-archive/%s/%s.json.gz",
                cutoffDate.format(YEAR_MONTH), cutoffDate);
        Path tempFile = null;
        List<String> allTraceIds = new ArrayList<>(totalCount);

        try {
            tempFile = Files.createTempFile("traces-archive-", ".json.gz");
            int totalSpanCount = writeArchiveBatched(tempFile, cutoff, allTraceIds);

            storageService.uploadObject(archiveContainer, blobName, tempFile);
            LOGGER.info("uploaded archive blob: container={}, blob={}, size={} bytes",
                    archiveContainer, blobName, Files.size(tempFile));

            // Delete spans first (child records), then traces (parent records)
            deleteSpansBatched(allTraceIds);
            traceCollection.delete(Filters.lt("started_at", cutoff));
            LOGGER.info("archived {} traces + {} spans to blob {}, cutoff={}",
                    totalCount, totalSpanCount, blobName, cutoff);
        } catch (IOException e) {
            throw new RuntimeException("failed to write archive to temp file", e);
        } finally {
            deleteTempFileQuietly(tempFile);
        }
        return totalCount;
    }

    /**
     * Stream traces + spans to a gzipped temp file in batches.
     * Returns the total number of spans written.
     */
    private int writeArchiveBatched(Path tempFile, ZonedDateTime cutoff, List<String> allTraceIds) throws IOException {
        int totalSpanCount = 0;
        int offset = 0;
        try (var gzipOut = new GZIPOutputStream(Files.newOutputStream(tempFile));
             var writer = new BufferedWriter(new OutputStreamWriter(gzipOut))) {
            while (true) {
                var batchQuery = new Query();
                batchQuery.filter = Filters.lt("started_at", cutoff);
                batchQuery.sort = Sorts.ascending("started_at");
                batchQuery.skip = offset;
                batchQuery.limit = ARCHIVE_BATCH_SIZE;
                var batch = traceCollection.find(batchQuery);
                if (batch.isEmpty()) break;

                var spansByTraceId = findSpansByTraceIds(batch);
                for (var trace : batch) {
                    writeArchiveLine(writer, "trace", trace);
                    var spans = spansByTraceId.getOrDefault(trace.traceId, List.of());
                    for (var span : spans) {
                        writeArchiveLine(writer, "span", span);
                    }
                    totalSpanCount += spans.size();
                    if (trace.traceId != null) allTraceIds.add(trace.traceId);
                }
                offset += batch.size();
                LOGGER.info("archive batch written: {} traces, offset={}", batch.size(), offset);
            }
        }
        LOGGER.info("serialized {} traces + {} spans to temp file, size={} bytes",
                allTraceIds.size(), totalSpanCount, Files.size(tempFile));
        return totalSpanCount;
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

    private void writeArchiveLine(BufferedWriter writer, String type, Object obj) throws IOException {
        String json = JsonUtil.toJson(obj);
        writer.write("{\"_type\":\"");
        writer.write(type);
        writer.write("\",");
        writer.write(json, 1, json.length() - 1);
        writer.newLine();
    }

    private Map<String, List<Span>> findSpansByTraceIds(List<Trace> traces) {
        List<String> traceIds = traces.stream()
                .map(t -> t.traceId)
                .filter(id -> id != null && !id.isEmpty())
                .toList();
        if (traceIds.isEmpty()) return Map.of();
        var spanQuery = new Query();
        spanQuery.filter = Filters.in("trace_id", traceIds);
        return spanCollection.find(spanQuery).stream()
                .filter(s -> s.traceId != null)
                .collect(Collectors.groupingBy(s -> s.traceId));
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
