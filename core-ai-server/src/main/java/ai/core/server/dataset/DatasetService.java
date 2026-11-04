package ai.core.server.dataset;

import ai.core.api.server.dataset.SchemaFieldView;
import ai.core.server.dataset.store.DatasetMetaStore;
import ai.core.server.dataset.store.DatasetRecordStore;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class DatasetService {
    public static DatasetType resolveType(Dataset dataset) {
        return dataset != null && dataset.type != null ? dataset.type : DatasetType.GENERAL;
    }

    public static List<SchemaFieldView> schemaViews(List<SchemaField> fields) {
        if (fields == null) return List.of();
        return fields.stream().map(field -> {
            var view = new SchemaFieldView();
            view.name = field.name;
            view.type = field.type != null ? field.type.name() : null;
            view.label = field.label;
            return view;
        }).toList();
    }

    @Inject
    DatasetMetaStore datasetMetaStore;

    @Inject
    DatasetRecordStore datasetRecordStore;

    public Dataset create(String name, String description, String userId, List<SchemaField> schema, DatasetType type) {
        var entity = new Dataset();
        entity.id = UUID.randomUUID().toString();
        entity.name = name;
        entity.description = description;
        entity.userId = userId;
        entity.type = type;
        entity.schema = schema;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;
        datasetMetaStore.create(entity);
        datasetRecordStore.onDatasetSchemaChanged(entity);
        return entity;
    }

    public List<Dataset> list(String query, Integer offset, Integer limit) {
        return datasetMetaStore.list(query, offset, limit);
    }

    public long count(String query) {
        return datasetMetaStore.count(query);
    }

    public Dataset get(String id) {
        return datasetMetaStore.get(id);
    }

    public Dataset update(String id, String name, String description, List<SchemaField> schema, DatasetType type) {
        var entity = datasetMetaStore.get(id);
        if (entity == null) return null;

        boolean schemaChanged = schema != null;
        if (name != null) entity.name = name;
        if (description != null) entity.description = description;
        if (schemaChanged) {
            entity.schema = schema;
        }
        if (type != null) entity.type = type;
        entity.updatedAt = ZonedDateTime.now();
        datasetMetaStore.update(entity);
        if (schemaChanged) datasetRecordStore.onDatasetSchemaChanged(entity);
        return entity;
    }

    public void delete(String id) {
        var entity = datasetMetaStore.get(id);
        if (entity == null) return;

        datasetRecordStore.deleteByDataset(id);
        datasetRecordStore.onDatasetDeleted(id);
        datasetMetaStore.delete(id);
    }
}
