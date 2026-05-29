package ai.core.server.dataset.tool;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.Dataset;
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

    private final String datasetId;
    private final DatasetRecordService recordService;

    private DeleteDatasetRecordTool(String datasetId, DatasetRecordService recordService) {
        this.datasetId = datasetId;
        this.recordService = recordService;
    }

    public static DeleteDatasetRecordTool create(String datasetId, DatasetRecordService recordService, Dataset dataset) {
        var tool = new DeleteDatasetRecordTool(datasetId, recordService);
        tool.setName(TOOL_NAME);
        tool.setDescription("Delete a record from the dataset \"" + (dataset != null ? dataset.name : "unknown") + "\".\n"
            + "Provide the record_id to delete. The dataset_id is automatically injected.");
        tool.setParameters(parameters());
        tool.setNeedAuth(false);
        tool.setDirectReturn(false);
        tool.setLlmVisible(true);
        tool.setDiscoverable(false);
        return tool;
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "record_id", "The ID of the record to delete.").required()
        );
    }

    @Override
    public ToolCallResult execute(String arguments) {
        var args = parseArguments(arguments);
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
