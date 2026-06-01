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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public final class InsertDatasetRecordTool extends ToolCall {
    public static final String TOOL_NAME = "insert_dataset_record";

    public static InsertDatasetRecordTool create(String datasetId, String agentId, String runId, DatasetRecordService recordService, Dataset dataset) {
        var tool = new InsertDatasetRecordTool(datasetId, agentId, runId, recordService, dataset != null ? dataset.schema : null);
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
        sb.append("Insert a new record into the dataset \"").append(dataset != null ? dataset.name : "unknown").append("\".\n");
        sb.append("Provide the data as a JSON object. The dataset_id and agent_id are automatically injected.\n");
        if (dataset != null && dataset.schema != null && !dataset.schema.isEmpty()) {
            sb.append("\nSchema fields:\n");
            for (var field : dataset.schema) {
                sb.append("  - ").append(field.name).append(" (").append(field.type.name().toLowerCase()).append(")");
                if (field.label != null) sb.append(": ").append(field.label);
                sb.append('\n');
            }
            sb.append("\nOnly include the fields listed above. Values must match the declared types.");
        } else {
            sb.append("\nNo schema defined — you may provide arbitrary key-value pairs.");
        }
        return sb.toString();
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(Map.class, "data", "A JSON object containing the record data with field keys matching the dataset schema.").required()
        );
    }

    private final String datasetId;
    private final String agentId;
    private final String runId;
    private final DatasetRecordService recordService;
    private final List<String> schemaFieldNames;

    private InsertDatasetRecordTool(String datasetId, String agentId, String runId, DatasetRecordService recordService, List<SchemaField> schema) {
        this.datasetId = datasetId;
        this.agentId = agentId;
        this.runId = runId;
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
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) args.get("data");
        if (data == null || data.isEmpty()) {
            return ToolCallResult.failed("data is required and must not be empty");
        }

        Map<String, Object> filtered = filterToSchema(data);
        if (filtered.isEmpty()) {
            return ToolCallResult.failed("none of the provided fields match the dataset schema: " + schemaFieldNames);
        }

        var effectiveRunId = runId != null ? runId : UUID.randomUUID().toString();
        var userId = context != null ? context.getUserId() : null;
        recordService.insert(new DatasetRecordService.InsertRequest(datasetId, agentId, effectiveRunId, ZonedDateTime.now(), filtered, userId, userId));

        var response = new LinkedHashMap<String, Object>();
        response.put("status", "created");
        response.put("dataset_id", datasetId);
        response.put("inserted_fields", new ArrayList<>(filtered.keySet()));
        response.put("message", "record inserted successfully");
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
