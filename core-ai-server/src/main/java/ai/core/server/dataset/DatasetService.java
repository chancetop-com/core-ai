package ai.core.server.dataset;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

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

    public List<Dataset> list() {
        return datasetCollection.find(new org.bson.Document());
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
}
