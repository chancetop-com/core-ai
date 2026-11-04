package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Control group: the native Mongo driver with {@code data} stored as a BSON sub-document, so the server
 * can filter and aggregate. Writes use a translated RFC 7386 patch when the patch can be expressed as
 * {@code $set}/{@code $unset}, and fall back to compare-and-swap when a path is not viable.
 *
 * @author stephen
 */
public class MongoNativeDatasetRecordStore implements DatasetRecordStore {
    private static final int MAX_CAS_RETRIES = 10;
    private static final int CAS_PATCH_THRESHOLD_BYTES = 8 * 1024;

    private static Document data(String json) {
        if (json == null || json.isBlank()) return new Document();
        var map = JsonUtil.toMap(json);
        MongoNativeFilter.validateData(map, "data");
        return new Document(map);
    }

    private static Date date(ZonedDateTime value) {
        return value != null ? Date.from(value.toInstant()) : null;
    }

    private static ZonedDateTime time(Date value) {
        return value != null ? value.toInstant().atZone(ZoneId.systemDefault()) : null;
    }

    private static String dataJson(Document document) {
        var data = document.get("data", Document.class);
        return data != null ? data.toJson() : "{}";
    }

    private static long version(Document document) {
        var value = document.get("version");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String effectiveUser(String userId) {
        return userId != null ? userId : "system";
    }

    private static Bson sessionFilter(String datasetId, String sessionId) {
        return Filters.and(Filters.eq("dataset_id", datasetId), Filters.eq("session_id", sessionId));
    }

    private static boolean isDuplicateKey(MongoWriteException e) {
        return e.getError().getCode() == 11000;
    }

    private static boolean isPathNotViable(MongoWriteException e) {
        return e.getError().getCode() == 28;
    }

    private final MongoCollection<Document> collection;

    public MongoNativeDatasetRecordStore(MongoClient client, String database, String collectionName) {
        this.collection = client.getDatabase(database).getCollection(collectionName);
    }

    /**
     * Mongo does not create indexes implicitly and the PoC server runs with notablescan, so a collection
     * must be prepared before the first read or upsert. Safe to call repeatedly.
     */
    public void ensureIndexes() {
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("dataset_id"), Indexes.descending("run_started_at")));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("dataset_id"), Indexes.ascending("agent_id"),
                Indexes.descending("run_started_at")));
        collection.createIndex(Indexes.ascending("user_id"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("dataset_id"), Indexes.ascending("session_id")),
                new IndexOptions().unique(true).partialFilterExpression(Filters.type("session_id", "string")));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("dataset_id"), Indexes.ascending("data.positive_count")));
    }

    @Override
    public void insert(DatasetRecord record) {
        collection.insertOne(document(record, 0L));
    }

    private Document document(DatasetRecord record, long version) {
        return new Document("_id", record.id)
                .append("dataset_id", record.datasetId)
                .append("agent_id", record.agentId)
                .append("run_id", record.runId)
                .append("session_id", record.sessionId)
                .append("user_id", record.userId)
                .append("data", data(record.data))
                .append("run_started_at", date(record.runStartedAt))
                .append("created_at", date(record.createdAt))
                .append("created_by", record.createdBy)
                .append("updated_at", date(record.updatedAt))
                .append("updated_by", record.updatedBy)
                .append("version", version);
    }

    @Override
    @SuppressFBWarnings("BC_UNCONFIRMED_CAST_OF_RETURN_VALUE")
    public DatasetRecordService.QueryResult query(DatasetRecordService.QueryRequest request) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("dataset_id", request.datasetId()));
        if (request.from() != null) filters.add(Filters.gte("run_started_at", date(request.from())));
        if (request.to() != null) filters.add(Filters.lte("run_started_at", date(request.to())));
        if (request.agentId() != null) filters.add(Filters.eq("agent_id", request.agentId()));
        filters.add(MongoNativeFilter.build(request.filter()));
        var filter = Filters.and(filters);

        int limit = request.limit() != null ? Math.min(Math.max(request.limit(), 1), DatasetRecordService.MAX_PAGE_SIZE) : 100;
        int offset = request.offset() != null ? Math.max(request.offset(), 0) : 0;

        var documents = collection.find(filter)
                .sort(Sorts.orderBy(Sorts.descending("run_started_at"), Sorts.ascending("_id")))
                .skip(offset)
                .limit(limit)
                .into(new ArrayList<>());
        var records = documents.stream().map(this::record).toList();
        var total = collection.countDocuments(filter);
        return new DatasetRecordService.QueryResult(DatasetRecordService.trimFields(records, request.fields()), total, false);
    }

    private DatasetRecord record(Document document) {
        var record = new DatasetRecord();
        record.id = document.getString("_id");
        record.datasetId = document.getString("dataset_id");
        record.agentId = document.getString("agent_id");
        record.runId = document.getString("run_id");
        record.sessionId = document.getString("session_id");
        record.userId = document.getString("user_id");
        record.data = dataJson(document);
        record.runStartedAt = time(document.getDate("run_started_at"));
        record.createdAt = time(document.getDate("created_at"));
        record.createdBy = document.getString("created_by");
        record.updatedAt = time(document.getDate("updated_at"));
        record.updatedBy = document.getString("updated_by");
        return record;
    }

    @Override
    public boolean update(String datasetId, String id, Map<String, Object> patch, String updatedBy) {
        var translated = MongoNativeFilter.translate(patch);
        if (translated != null && applyPartialUpdate(datasetId, id, translated, updatedBy)) return true;
        return casUpdate(datasetId, id, patch, updatedBy);
    }

    private boolean applyPartialUpdate(String datasetId, String id, MongoNativeFilter.Patch patch, String updatedBy) {
        try {
            var result = collection.updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("dataset_id", datasetId)),
                    partialUpdate(patch, updatedBy));
            return result.getMatchedCount() > 0;
        } catch (MongoWriteException e) {
            if (isPathNotViable(e)) return false;
            throw e;
        }
    }

    private Document partialUpdate(MongoNativeFilter.Patch patch, String updatedBy) {
        var assignments = new LinkedHashMap<>(patch.assignments());
        assignments.put("updated_at", new Date());
        assignments.put("updated_by", updatedBy);
        var update = new Document("$set", assignments);
        if (!patch.removals().isEmpty()) update.append("$unset", patch.removals());
        return update;
    }

    private boolean casUpdate(String datasetId, String id, Map<String, Object> patch, String updatedBy) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            var current = collection.find(Filters.and(Filters.eq("_id", id), Filters.eq("dataset_id", datasetId))).first();
            if (current == null) return false;
            var merged = DatasetRecordService.merge(JsonUtil.toMap(dataJson(current)), patch);
            var record = record(current);
            record.data = JsonUtil.toJson(merged);
            record.updatedAt = ZonedDateTime.now();
            record.updatedBy = updatedBy;
            var replaced = collection.replaceOne(Filters.and(Filters.eq("_id", id), Filters.eq("version", version(current))),
                    document(record, version(current) + 1));
            if (replaced.getModifiedCount() > 0) return true;
        }
        throw new IllegalStateException("concurrent update retries exhausted, id=" + id);
    }

    @Override
    public boolean delete(String datasetId, String id) {
        return collection.deleteOne(Filters.and(Filters.eq("_id", id), Filters.eq("dataset_id", datasetId))).getDeletedCount() > 0;
    }

    @Override
    public long deleteByDataset(String datasetId) {
        return collection.deleteMany(Filters.eq("dataset_id", datasetId)).getDeletedCount();
    }

    @Override
    public Optional<DatasetRecord> findBySession(String datasetId, String sessionId) {
        var document = collection.find(sessionFilter(datasetId, sessionId)).first();
        return Optional.ofNullable(document).map(this::record);
    }

    @Override
    public void upsertBySession(String datasetId, String sessionId, String dataJson, String agentId, String userId) {
        DatasetRecordService.checkStateSize(dataJson);
        var now = new Date();
        var update = new Document("$set", new Document("data", data(dataJson))
                .append("run_started_at", now)
                .append("updated_at", now)
                .append("updated_by", effectiveUser(userId)))
                .append("$setOnInsert", new Document("_id", UUID.randomUUID().toString())
                        .append("agent_id", agentId)
                        .append("user_id", userId)
                        .append("created_at", now)
                        .append("created_by", effectiveUser(userId))
                        .append("version", 0L));
        retryDuplicateKey(() -> collection.updateOne(sessionFilter(datasetId, sessionId), update, new UpdateOptions().upsert(true)));
    }

    @Override
    public void patchBySession(String datasetId, String sessionId, String patchJson, String agentId, String userId) {
        var patch = JsonUtil.toMap(patchJson);
        var translated = MongoNativeFilter.translate(patch);
        boolean large = patchJson.getBytes(StandardCharsets.UTF_8).length > CAS_PATCH_THRESHOLD_BYTES;
        if (translated != null && !large && applyPatchUpsert(datasetId, sessionId, translated, agentId, userId)) return;
        casPatchSession(datasetId, sessionId, patch, agentId, userId);
    }

    private boolean applyPatchUpsert(String datasetId, String sessionId, MongoNativeFilter.Patch patch, String agentId, String userId) {
        var now = new Date();
        var assignments = new LinkedHashMap<>(patch.assignments());
        assignments.put("run_started_at", now);
        assignments.put("updated_at", now);
        assignments.put("updated_by", effectiveUser(userId));
        var update = new Document("$set", assignments).append("$setOnInsert", new Document("_id", UUID.randomUUID().toString())
                .append("agent_id", agentId)
                .append("user_id", userId)
                .append("created_at", now)
                .append("created_by", effectiveUser(userId))
                .append("version", 0L));
        if (!patch.removals().isEmpty()) update.append("$unset", patch.removals());
        try {
            retryDuplicateKey(() -> collection.updateOne(sessionFilter(datasetId, sessionId), update, new UpdateOptions().upsert(true)));
            return true;
        } catch (MongoWriteException e) {
            if (isPathNotViable(e)) return false;
            throw e;
        }
    }

    private void casPatchSession(String datasetId, String sessionId, Map<String, Object> patch, String agentId, String userId) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            var current = collection.find(sessionFilter(datasetId, sessionId)).first();
            if (current == null) {
                createFromPatch(datasetId, sessionId, patch, agentId, userId);
                return;
            }
            var merged = DatasetRecordService.merge(JsonUtil.toMap(dataJson(current)), patch);
            var json = JsonUtil.toJson(merged);
            DatasetRecordService.checkStateSize(json);
            var record = record(current);
            record.data = json;
            record.runStartedAt = ZonedDateTime.now();
            record.updatedAt = record.runStartedAt;
            record.updatedBy = effectiveUser(userId);
            var replaced = collection.replaceOne(Filters.and(Filters.eq("_id", record.id), Filters.eq("version", version(current))),
                    document(record, version(current) + 1));
            if (replaced.getModifiedCount() > 0) return;
        }
        throw new IllegalStateException("concurrent patch retries exhausted, sessionId=" + sessionId);
    }

    private void createFromPatch(String datasetId, String sessionId, Map<String, Object> patch, String agentId, String userId) {
        var json = JsonUtil.toJson(patch);
        DatasetRecordService.checkStateSize(json);
        var now = ZonedDateTime.now();
        var record = new DatasetRecord();
        record.id = UUID.randomUUID().toString();
        record.datasetId = datasetId;
        record.agentId = agentId;
        record.sessionId = sessionId;
        record.data = json;
        record.runStartedAt = now;
        record.userId = userId;
        record.createdAt = now;
        record.createdBy = effectiveUser(userId);
        record.updatedAt = now;
        record.updatedBy = effectiveUser(userId);
        try {
            collection.insertOne(document(record, 0L));
        } catch (MongoWriteException e) {
            if (!isDuplicateKey(e)) throw e;
        }
    }

    private void retryDuplicateKey(Runnable action) {
        try {
            action.run();
        } catch (MongoWriteException e) {
            if (!isDuplicateKey(e)) throw e;
            action.run();
        }
    }

    @Override
    @SuppressFBWarnings("BC_UNCONFIRMED_CAST_OF_RETURN_VALUE")
    public AggregateResult aggregate(AggregateRequest request) {
        var pipeline = MongoNativeAggregation.build(request);
        var documents = collection.aggregate(pipeline.stages()).into(new ArrayList<>());
        var rows = new ArrayList<List<Object>>(documents.size());
        for (var document : documents) {
            rows.add(MongoNativeAggregation.normalize(document, request.metrics.size()));
        }
        return new AggregateResult(pipeline.columns(), rows);
    }
}
