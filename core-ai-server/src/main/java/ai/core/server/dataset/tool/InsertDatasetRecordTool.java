package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetOpPayloads;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetRecordWriteRules;
import ai.core.server.dataset.DatasetService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.time.ZonedDateTime;
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
        tool.setNeedAuth(Boolean.FALSE);
        tool.setDirectReturn(Boolean.FALSE);
        tool.setLlmVisible(Boolean.TRUE);
        tool.setDiscoverable(Boolean.FALSE);
        return tool;
    }

    private static String buildDescription(DatasetService datasetService, DatasetAccessRegistry registry) {
        return "Insert a new record into a dataset.\nProvide the data as a JSON object and the target dataset_id.\n"
                + QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry);
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
        var datasetRef = getStringValue(args, "dataset_id");
        if (datasetRef == null || datasetRef.isBlank()) {
            return ToolCallResult.failed("dataset_id is required");
        }
        var ambiguous = registry.ambiguousMessage(datasetRef);
        if (ambiguous != null) {
            return ToolCallResult.failed(ambiguous);
        }
        var datasetId = registry.resolveId(datasetRef);
        if (!registry.isWritable(datasetRef)) {
            return ToolCallResult.failed("write access denied to dataset: " + datasetRef);
        }
        var sessionError = QueryDatasetRecordsTool.sessionDatasetAccessError(datasetService, datasetId);
        if (sessionError != null) {
            return ToolCallResult.failed(sessionError);
        }

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) args.get("data");
        if (data == null || data.isEmpty()) {
            return ToolCallResult.failed("data is required and must not be empty");
        }

        var dataset = datasetService.get(datasetId);
        var filtered = DatasetRecordWriteRules.filterToSchema(dataset, data);
        if (filtered.isEmpty()) {
            return ToolCallResult.failed("none of the provided fields match the dataset schema: "
                    + DatasetRecordWriteRules.schemaFieldNames(dataset));
        }

        var effectiveRunId = runId != null ? runId : UUID.randomUUID().toString();
        var userId = context != null ? context.getUserId() : null;
        recordService.insert(new DatasetRecordService.InsertRequest(datasetId, agentId, effectiveRunId, ZonedDateTime.now(), filtered, userId, userId));

        return ToolCallResult.completed(DatasetOpPayloads.recordInserted(datasetId, filtered.keySet()));
    }
}
