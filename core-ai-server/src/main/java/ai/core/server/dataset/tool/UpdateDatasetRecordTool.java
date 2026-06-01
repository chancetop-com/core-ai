package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public final class UpdateDatasetRecordTool extends ToolCall {
    public static final String TOOL_NAME = "update_dataset_record";

    public static UpdateDatasetRecordTool create(String datasetId, DatasetRecordService recordService, Dataset dataset) {
        var tool = new UpdateDatasetRecordTool(datasetId, recordService, dataset != null ? dataset.schema : null);
        tool.setName(TOOL_NAME);
        tool.setDescription(buildDescription(dataset));
        tool.setParameters(parameters());
        tool.setNeedAuth(false);
        tool.setDirectReturn(false);
        tool.setLlmVisible(true);
        tool.setDiscoverable(false);
        return tool;
    }

    @SuppressWarnings({"PMD.ConsecutiveLiteralAppends", "PMD.AppendCharacterWithChar", "PMD.ConsecutiveAppendsShouldReuse", "PMD.UseLocaleWithCaseConversions"})
    private static String buildDescription(Dataset dataset) {
        var sb = new StringBuilder(320);
        sb.append("Update an existing record in the dataset \"").append(dataset != null ? dataset.name : "unknown").append("\".\n");
        sb.append("Provide the record_id and the updated field values as a JSON object.\n").append("The dataset_id is automatically injected.\n");
        if (dataset != null && dataset.schema != null && !dataset.schema.isEmpty()) {
            sb.append("\nSchema fields (only include fields you want to update):\n");
            for (var field : dataset.schema) {
                sb.append("  - ").append(field.name).append(" (").append(field.type.name().toLowerCase()).append(")");
                if (field.label != null) sb.append(": ").append(field.label);
                sb.append('\n');
            }
            sb.append("\nYou may provide a subset of fields — only the provided fields will be updated.");
        }
        return sb.toString();
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "record_id", "The ID of the record to update.").required(),
            ToolCallParameters.ParamSpec.of(Map.class, "data", "A JSON object containing the field values to update.").required()
        );
    }

    private final String datasetId;
    private final DatasetRecordService recordService;
    private final List<String> schemaFieldNames;

    private UpdateDatasetRecordTool(String datasetId, DatasetRecordService recordService, List<SchemaField> schema) {
        this.datasetId = datasetId;
        this.recordService = recordService;
        this.schemaFieldNames = schema != null ? schema.stream().map(f -> f.name).collect(Collectors.toList()) : List.of();
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var recordId = getStringValue(args, "record_id");
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) args.get("data");

        if (recordId == null || recordId.isBlank()) {
            return ToolCallResult.failed("record_id is required");
        }
        if (data == null || data.isEmpty()) {
            return ToolCallResult.failed("data is required and must not be empty");
        }

        Map<String, Object> filtered = filterToSchema(data);
        if (filtered.isEmpty()) {
            return ToolCallResult.failed("none of the provided fields match the dataset schema: " + schemaFieldNames);
        }

        var updatedBy = context != null ? context.getUserId() : null;
        var updated = recordService.update(recordId, filtered, updatedBy);
        if (!updated) {
            return ToolCallResult.failed("record not found, id=" + recordId);
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("status", "updated");
        response.put("record_id", recordId);
        response.put("dataset_id", datasetId);
        response.put("updated_fields", new ArrayList<>(filtered.keySet()));
        response.put("message", "record updated successfully");
        return ToolCallResult.completed(JsonUtil.toJson(response));
    }

    private Map<String, Object> filterToSchema(Map<String, Object> data) {
        if (schemaFieldNames.isEmpty()) return new LinkedHashMap<>(data);
        var result = new LinkedHashMap<String, Object>();
        for (var fieldName : schemaFieldNames) {
            if (data.containsKey(fieldName)) {
                result.put(fieldName, data.get(fieldName));
            }
        }
        return result;
    }
}
