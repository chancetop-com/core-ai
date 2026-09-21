package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetOpPayloads;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;

/**
 * @author stephen
 */
public final class DeleteDatasetRecordTool extends ToolCall {
    public static final String TOOL_NAME = "delete_dataset_record";

    public static DeleteDatasetRecordTool create(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new DeleteDatasetRecordTool(datasetService, recordService, registry);
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
        return "Delete a record from a dataset.\nProvide the dataset_id and record_id to delete.\n"
                + QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the dataset. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(String.class, "record_id", "The ID of the record to delete.").required()
        );
    }

    private final DatasetService datasetService;
    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private DeleteDatasetRecordTool(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
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
        if (!registry.isDeletable(datasetRef)) {
            return ToolCallResult.failed("delete access denied to dataset: " + datasetRef);
        }
        var sessionError = QueryDatasetRecordsTool.sessionDatasetAccessError(datasetService, datasetId);
        if (sessionError != null) {
            return ToolCallResult.failed(sessionError);
        }

        var recordId = getStringValue(args, "record_id");
        if (recordId == null || recordId.isBlank()) {
            return ToolCallResult.failed("record_id is required");
        }

        var deleted = recordService.delete(datasetId, recordId);
        if (!deleted) {
            return ToolCallResult.failed("record not found, id=" + recordId);
        }
        return ToolCallResult.completed(DatasetOpPayloads.recordDeleted(recordId, datasetId));
    }
}
