package ai.core.server.dataset;

import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class DatasetRecordService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetRecordService.class);

    @Inject
    MongoCollection<DatasetRecord> datasetRecordCollection;

    public void insert(InsertRequest request) {
        var record = new DatasetRecord();
        record.id = UUID.randomUUID().toString();
        record.datasetId = request.datasetId;
        record.agentId = request.agentId;
        record.runId = request.runId;
        record.data = JsonUtil.toJson(request.data);
        record.runStartedAt = request.runStartedAt;
        record.userId = request.userId;
        record.createdBy = request.createdBy;
        record.createdAt = ZonedDateTime.now();
        record.updatedAt = record.createdAt;
        record.updatedBy = request.createdBy;
        datasetRecordCollection.insert(record);
        LOGGER.info("dataset record inserted, datasetId={}, runId={}", request.datasetId, request.runId);
    }

    public QueryResult query(QueryRequest request) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("dataset_id", request.datasetId));
        if (request.from != null) filters.add(Filters.gte("run_started_at", request.from));
        if (request.to != null) filters.add(Filters.lte("run_started_at", request.to));
        if (request.agentId != null) filters.add(Filters.eq("agent_id", request.agentId));

        var filter = Filters.and(filters);

        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("run_started_at");
        query.limit = request.limit != null ? request.limit : 100;
        query.skip = request.offset != null ? request.offset : 0;

        if (request.fields != null && !request.fields.isEmpty()) {
            var projection = new BsonDocument();
            projection.append("_id", new BsonInt32(1));
            projection.append("run_id", new BsonInt32(1));
            projection.append("agent_id", new BsonInt32(1));
            projection.append("run_started_at", new BsonInt32(1));
            projection.append("data", new BsonInt32(1));
            query.projection = projection;
        }

        var records = datasetRecordCollection.find(query);
        var total = datasetRecordCollection.count(filter);
        return new QueryResult(records, total);
    }

    public boolean update(String id, Map<String, Object> data, String updatedBy) {
        var record = datasetRecordCollection.get(id).orElse(null);
        if (record == null) return false;
        record.data = JsonUtil.toJson(data);
        record.updatedAt = ZonedDateTime.now();
        record.updatedBy = updatedBy;
        datasetRecordCollection.replace(record);
        LOGGER.info("dataset record updated, id={}", id);
        return true;
    }

    public boolean delete(String id) {
        var record = datasetRecordCollection.get(id).orElse(null);
        if (record == null) return false;
        datasetRecordCollection.delete(id);
        LOGGER.info("dataset record deleted, id={}", id);
        return true;
    }

    public record QueryResult(List<DatasetRecord> records, long total) { }

    public record InsertRequest(String datasetId, String agentId, String runId, ZonedDateTime runStartedAt, Map<String, Object> data, String userId, String createdBy) { }
    public record QueryRequest(String datasetId, ZonedDateTime from, ZonedDateTime to, List<String> fields, Integer limit, Integer offset, String agentId) { }
}
