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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the session state document for the current session into a SESSION dataset.
 * Full-document overwrite: the stored state is replaced entirely on every call.
 *
 * @author stephen
 */
public final class SetSessionStateTool extends ToolCall {
    public static final String TOOL_NAME = "set_session_state";

    public static SetSessionStateTool create(String agentId, DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new SetSessionStateTool(agentId, datasetService, recordService, registry);
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
        return "Persist the current session state as a single JSON document in a session dataset.\n"
                + "Subsequent calls fully replace the previous state — always pass the complete state object. "
                + "State survives across turns and session rebuilds.\n"
                + QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the session dataset. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(Map.class, "data", "A JSON object containing the complete session state. Required — it fully replaces the previously stored state.").required()
        );
    }

    private final String agentId;
    private final DatasetService datasetService;
    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private SetSessionStateTool(String agentId, DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
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
        var dataJson = JsonUtil.toJson(data);
        if (dataJson.getBytes(StandardCharsets.UTF_8).length > DatasetRecordService.MAX_STATE_BYTES) {
            return ToolCallResult.failed("state too large, max " + (DatasetRecordService.MAX_STATE_BYTES / 1024) + " KB");
        }

        recordService.upsertBySession(datasetId, context.getSessionId(), dataJson, agentId, context.getUserId());

        var response = new LinkedHashMap<String, Object>();
        response.put("status", "saved");
        response.put("dataset_id", datasetId);
        response.put("session_id", context.getSessionId());
        return ToolCallResult.completed(JsonUtil.toJson(response));
    }
}
