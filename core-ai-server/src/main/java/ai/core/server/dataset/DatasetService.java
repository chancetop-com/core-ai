package ai.core.server.dataset;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class DatasetService {
    @Inject
    MongoCollection<Dataset> datasetCollection;

    @Inject
    MongoCollection<DatasetRecord> datasetRecordCollection;

    public Dataset create(String name, String description, String userId, List<ai.core.server.domain.SchemaField> schema) {
        var entity = new Dataset();
        entity.id = UUID.randomUUID().toString();
        entity.name = name;
        entity.description = description;
        entity.userId = userId;
        entity.schema = schema;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;
        datasetCollection.insert(entity);
        return entity;
    }

    public List<Dataset> list(String query, Integer offset, Integer limit) {
        var dbQuery = new Query();
        dbQuery.filter = filter(query);
        dbQuery.sort = Sorts.descending("updated_at");
        if (offset != null || limit != null) {
            dbQuery.skip = Math.max(0, offset != null ? offset : 0);
            dbQuery.limit = Math.min(Math.max(limit != null ? limit : 20, 1), 100);
        }
        return datasetCollection.find(dbQuery);
    }

    public long count(String query) {
        return datasetCollection.count(filter(query));
    }

    public Dataset get(String id) {
        return datasetCollection.get(id).orElse(null);
    }

    public Dataset update(String id, String name, String description, List<ai.core.server.domain.SchemaField> schema) {
        var entity = datasetCollection.get(id).orElse(null);
        if (entity == null) return null;

        if (name != null) entity.name = name;
        if (description != null) entity.description = description;
        if (schema != null) {
            entity.schema = schema;
        }
        entity.updatedAt = ZonedDateTime.now();
        datasetCollection.replace(entity);
        return entity;
    }

    public void delete(String id) {
        var entity = datasetCollection.get(id).orElse(null);
        if (entity == null) return;

        datasetRecordCollection.delete(Filters.eq("dataset_id", id));
        datasetCollection.delete(id);
    }

    private Bson filter(String query) {
        if (query == null || query.isBlank()) return Filters.empty();
        var pattern = Pattern.quote(query.trim());
        var filters = new ArrayList<Bson>();
        filters.add(Filters.regex("name", pattern, "i"));
        filters.add(Filters.regex("description", pattern, "i"));
        filters.add(Filters.regex("user_id", pattern, "i"));
        return Filters.or(filters);
    }
}
