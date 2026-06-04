package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
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

/**
 * @author stephen
 */
public final class InsertDatasetRecordTool extends ToolCall {
    public static final String TOOL_NAME = "insert_dataset_record";

    public static InsertDatasetRecordTool create(String agentId, String runId, DatasetService datasetService,
                                                  DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new InsertDatasetRecordTool(agentId, runId, datasetService, recordService, registry);
        tool.setName(TOOL_NAME);
        tool.setDescription(buildDescription(datasetService, registry));
        tool.setParameters(parameters());
        tool.setNeedAuth(false);
        tool.setDirectReturn(false);
        tool.setLlmVisible(true);
        tool.setDiscoverable(false);
        return tool;
    }

    private static String buildDescription(DatasetService datasetService, DatasetAccessRegistry registry) {
        var sb = new StringBuilder(512);
        sb.append("Insert a new record into a dataset.\n");
        sb.append("Provide the data as a JSON object and the target dataset_id.\n");
        sb.append(QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry));
        return sb.toString();
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the dataset to insert into. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(Map.class, "data", "A JSON object containing the record data with field keys matching the dataset schema.").required()
        );
    }

    private final String agentId;
    private final String runId;
    private final DatasetService datasetService;
    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private InsertDatasetRecordTool(String agentId, String runId, DatasetService datasetService,
                                    DatasetRecordService recordService, DatasetAccessRegistry registry) {
        this.agentId = agentId;
        this.runId = runId;
        this.datasetService = datasetService;
        this.recordService = recordService;
        this.registry = registry;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var datasetId = getStringValue(args, "dataset_id");
        if (datasetId == null || datasetId.isBlank()) {
            return ToolCallResult.failed("dataset_id is required");
        }
        if (!registry.isWritable(datasetId)) {
            return ToolCallResult.failed("write access denied to dataset: " + datasetId);
        }

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) args.get("data");
        if (data == null || data.isEmpty()) {
            return ToolCallResult.failed("data is required and must not be empty");
        }

        var dataset = datasetService.get(datasetId);
        Map<String, Object> filtered = filterToSchema(data, dataset);
        if (filtered.isEmpty()) {
            var schemaFieldNames = dataset != null && dataset.schema != null
                ? dataset.schema.stream().map(f -> f.name).toList() : List.of();
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

    private Map<String, Object> filterToSchema(Map<String, Object> data, ai.core.server.domain.Dataset dataset) {
        if (dataset == null || dataset.schema == null || dataset.schema.isEmpty()) return new LinkedHashMap<>(data);
        var schemaFieldNames = dataset.schema.stream().map(f -> f.name).toList();
        var result = new LinkedHashMap<String, Object>();
        for (var fieldName : schemaFieldNames) {
            if (data.containsKey(fieldName)) {
                result.put(fieldName, data.get(fieldName));
            }
        }
        return result;
    }
}
