package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetOpPayloads;
import ai.core.server.dataset.DatasetService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;

/**
 * Renames one schema field and moves the same key in every record. The view is rebuilt only after the
 * record rewrite completes, so a failed operation can be retried without leaving a half-renamed view.
 *
 * @author stephen
 */
public final class RenameDatasetFieldTool extends ToolCall {
    public static final String TOOL_NAME = "rename_dataset_field";

    public static RenameDatasetFieldTool create(DatasetService datasetService, DatasetAccessRegistry registry) {
        var tool = new RenameDatasetFieldTool(datasetService, registry);
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
        return "Rename a field in a dataset. Provide dataset_id, the existing field name (from) and the new name (to). "
                + "All records are updated and the generated view is rebuilt; the new name must not already exist.\n"
                + QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the dataset. Required — choose from available datasets listed above.").required(),
                ToolCallParameters.ParamSpec.of(String.class, "from", "The current field name.").required(),
                ToolCallParameters.ParamSpec.of(String.class, "to", "The new field name; it must not already exist.").required()
        );
    }

    private final DatasetService datasetService;
    private final DatasetAccessRegistry registry;

    private RenameDatasetFieldTool(DatasetService datasetService, DatasetAccessRegistry registry) {
        this.datasetService = datasetService;
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

        var from = getStringValue(args, "from");
        var to = getStringValue(args, "to");
        try {
            var result = datasetService.renameField(datasetId, from, to, context != null ? context.getUserId() : null);
            return ToolCallResult.completed(DatasetOpPayloads.fieldRenamed(datasetId, from, to, result.records()));
        } catch (IllegalArgumentException e) {
            return ToolCallResult.failed(e.getMessage());
        }
    }
}
