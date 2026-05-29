package ai.core.server.web;

import ai.core.api.server.DatasetWebService;
import ai.core.api.server.dataset.CreateDatasetRequest;
import ai.core.api.server.dataset.DatasetRecordView;
import ai.core.api.server.dataset.DatasetView;
import ai.core.api.server.dataset.ListDatasetRecordsResponse;
import ai.core.api.server.dataset.ListDatasetsResponse;
import ai.core.api.server.dataset.SchemaFieldView;
import ai.core.api.server.dataset.UpdateDatasetRequest;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.domain.User;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * @author stephen
 */
public class DatasetWebServiceImpl implements DatasetWebService {
    @Inject
    WebContext webContext;

    @Inject
    DatasetService datasetService;

    @Inject
    DatasetRecordService datasetRecordService;

    @Inject
    MongoCollection<User> userCollection;

    @Override
    public DatasetView create(CreateDatasetRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        var schema = toSchemaFields(request.schema);
        var entity = datasetService.create(request.name, request.description, userId, schema);
        return toView(entity);
    }

    @Override
    public ListDatasetsResponse list() {
        var entities = datasetService.list();
        var response = new ListDatasetsResponse();
        response.datasets = entities.stream().map(this::toView).toList();
        response.total = (long) response.datasets.size();
        return response;
    }

    @Override
    public DatasetView get(String id) {
        var entity = datasetService.get(id);
        if (entity == null) throw new RuntimeException("dataset not found, id=" + id);
        return toView(entity);
    }

    @Override
    public DatasetView update(String id, UpdateDatasetRequest request) {
        var schema = request.schema != null ? toSchemaFields(request.schema) : null;
        var entity = datasetService.update(id, request.name, request.description, schema);
        if (entity == null) throw new RuntimeException("dataset not found, id=" + id);
        return toView(entity);
    }

    @Override
    public void delete(String id) {
        datasetService.delete(id);
    }

    @Override
    public ListDatasetRecordsResponse listRecords(String id) {
        var dataset = datasetService.get(id);
        if (dataset == null) throw new RuntimeException("dataset not found, id=" + id);

        var params = webContext.request().queryParams();
        var fromStr = params != null ? params.get("from") : null;
        var toStr = params != null ? params.get("to") : null;
        var fieldsStr = params != null ? params.get("fields") : null;
        var limitStr = params != null ? params.get("limit") : null;
        var offsetStr = params != null ? params.get("offset") : null;
        var agentId = params != null ? params.get("agent_id") : null;

        ZonedDateTime from = null;
        ZonedDateTime to = null;
        try {
            if (fromStr != null) from = ZonedDateTime.parse(fromStr, DateTimeFormatter.ISO_DATE_TIME);
            if (toStr != null) to = ZonedDateTime.parse(toStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("invalid date format, use ISO 8601", e);
        }

        Integer limit = limitStr != null ? Integer.parseInt(limitStr) : null;
        Integer offset = offsetStr != null ? Integer.parseInt(offsetStr) : null;

        List<String> fields = fieldsStr != null ? Arrays.asList(fieldsStr.split(",")) : null;

        var result = datasetRecordService.query(id, from, to, fields, limit, offset, agentId);

        var response = new ListDatasetRecordsResponse();
        response.records = result.records().stream().map(this::toRecordView).toList();
        response.total = result.total();
        return response;
    }

    private DatasetView toView(ai.core.server.domain.Dataset entity) {
        var view = new DatasetView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.schema = toSchemaFieldViews(entity.schema);
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        view.createdBy = entity.userId != null
                ? userCollection.get(entity.userId).map(u -> u.name).orElse(entity.userId)
                : null;
        return view;
    }

    private DatasetRecordView toRecordView(DatasetRecord entity) {
        var view = new DatasetRecordView();
        view.id = entity.id;
        view.runId = entity.runId;
        view.agentId = entity.agentId;
        view.runStartedAt = entity.runStartedAt != null ? entity.runStartedAt.format(DateTimeFormatter.ISO_DATE_TIME) : null;
        view.data = entity.data;
        return view;
    }

    private List<SchemaField> toSchemaFields(List<SchemaFieldView> views) {
        if (views == null) return null;
        return views.stream().map(v -> {
            var field = new SchemaField();
            field.name = v.name;
            field.type = v.type != null ? SchemaFieldType.valueOf(v.type) : null;
            field.label = v.label;
            return field;
        }).toList();
    }

    private List<SchemaFieldView> toSchemaFieldViews(List<SchemaField> fields) {
        if (fields == null) return null;
        return fields.stream().map(f -> {
            var view = new SchemaFieldView();
            view.name = f.name;
            view.type = f.type != null ? f.type.name() : null;
            view.label = f.label;
            return view;
        }).toList();
    }
}
