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

    public void insert(String datasetId, String agentId, String runId, ZonedDateTime runStartedAt, Map<String, Object> data) {
        var record = new DatasetRecord();
        record.id = UUID.randomUUID().toString();
        record.datasetId = datasetId;
        record.agentId = agentId;
        record.runId = runId;
        record.data = JsonUtil.toJson(data);
        record.runStartedAt = runStartedAt;
        record.createdAt = ZonedDateTime.now();
        datasetRecordCollection.insert(record);
        LOGGER.info("dataset record inserted, datasetId={}, runId={}", datasetId, runId);
    }

    public QueryResult query(String datasetId, ZonedDateTime from, ZonedDateTime to, List<String> fields, Integer limit, Integer offset, String agentId) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("dataset_id", datasetId));
        if (from != null) filters.add(Filters.gte("run_started_at", from));
        if (to != null) filters.add(Filters.lte("run_started_at", to));
        if (agentId != null) filters.add(Filters.eq("agent_id", agentId));

        var filter = Filters.and(filters);

        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("run_started_at");
        query.limit = limit != null ? limit : 100;
        query.skip = offset != null ? offset : 0;

        if (fields != null && !fields.isEmpty()) {
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

    public record QueryResult(List<DatasetRecord> records, long total) {}
}
