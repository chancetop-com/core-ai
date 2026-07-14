package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * @author stephen
 */
public final class DeleteDatasetRecordTool extends ToolCall {
    public static final String TOOL_NAME = "delete_dataset_record";

    public static DeleteDatasetRecordTool create(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new DeleteDatasetRecordTool(recordService, registry);
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
        return "Delete a record from a dataset.\nProvide the dataset_id and record_id to delete.\n"
                + QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the dataset. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(String.class, "record_id", "The ID of the record to delete.").required()
        );
    }

    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private DeleteDatasetRecordTool(DatasetRecordService recordService, DatasetAccessRegistry registry) {
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
        if (!registry.isDeletable(datasetId)) {
            return ToolCallResult.failed("delete access denied to dataset: " + datasetId);
        }

        var recordId = getStringValue(args, "record_id");
        if (recordId == null || recordId.isBlank()) {
            return ToolCallResult.failed("record_id is required");
        }

        var deleted = recordService.delete(recordId);
        if (!deleted) {
            return ToolCallResult.failed("record not found, id=" + recordId);
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "deleted");
        response.put("record_id", recordId);
        response.put("dataset_id", datasetId);
        response.put("message", "record deleted successfully");
        return ToolCallResult.completed(JsonUtil.toJson(response));
    }
}
