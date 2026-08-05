package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.DatasetType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field-level update of the session state: merges the provided partial object into the
 * stored state (RFC 7386 JSON Merge Patch) and writes it back. Only the provided fields
 * are changed, so the agent does not need to send the full state.
 *
 * @author stephen
 */
public final class UpdateSessionStateTool extends ToolCall {
    public static final String TOOL_NAME = "update_session_state";

    public static UpdateSessionStateTool create(String agentId, DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new UpdateSessionStateTool(agentId, datasetService, recordService, registry);
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
        return "Update specific fields of the session state stored in a session dataset.\n"
                + "Only the provided fields are changed — pass a partial object. "
                + "Nested objects merge recursively, arrays and scalars replace entirely, null deletes a field. "
                + "State survives across turns and session rebuilds.\n"
                + QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the session dataset. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(Map.class, "data", "A JSON object with the fields to change. Required — only these fields are merged into the stored state.").required()
        );
    }

    private final String agentId;
    private final DatasetService datasetService;
    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private UpdateSessionStateTool(String agentId, DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        this.agentId = agentId;
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
        if (context == null || context.getSessionId() == null) {
            return ToolCallResult.failed("session context required");
        }
        if (!registry.isWritable(datasetId)) {
            return ToolCallResult.failed("write access denied to dataset: " + datasetId);
        }
        var dataset = datasetService.get(datasetId);
        if (dataset == null) {
            return ToolCallResult.failed("dataset not found: " + datasetId);
        }
        if (DatasetService.resolveType(dataset) != DatasetType.SESSION) {
            return ToolCallResult.failed("not a session dataset, use dataset record tools instead: " + datasetId);
        }

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) args.get("data");
        if (data == null || data.isEmpty()) {
            return ToolCallResult.failed("data is required and must not be empty");
        }

        try {
            recordService.patchBySession(datasetId, context.getSessionId(), JsonUtil.toJson(data), agentId, context.getUserId());
        } catch (IllegalArgumentException e) {
            return ToolCallResult.failed(e.getMessage());
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("status", "updated");
        response.put("dataset_id", datasetId);
        response.put("session_id", context.getSessionId());
        response.put("updated_fields", data.keySet());
        return ToolCallResult.completed(JsonUtil.toJson(response));
    }
}
