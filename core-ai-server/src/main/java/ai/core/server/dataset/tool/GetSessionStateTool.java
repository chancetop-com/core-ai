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
 * Reads the session state document for the current session from a SESSION dataset.
 *
 * @author stephen
 */
public final class GetSessionStateTool extends ToolCall {
    public static final String TOOL_NAME = "get_session_state";
    private static final Object MISSING_FIELD = new Object();

    public static GetSessionStateTool create(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new GetSessionStateTool(datasetService, recordService, registry);
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
        return "Get the current session state stored in a session dataset.\n"
                + "Each session has at most one state record per dataset. Call this at the beginning of every turn to restore session context. "
                + "Pass fields to retrieve only the listed top-level fields (comma-separated) and avoid loading large state.\n"
                + QueryDatasetRecordsTool.buildAvailableDatasetsSection(datasetService, registry);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the session dataset. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(String.class, "fields", "Comma-separated top-level field names to include in the state. If not specified, the full state is returned.")
        );
    }

    private static Map<String, Object> selectFields(Map<String, Object> state, String fields) {
        var selected = new LinkedHashMap<String, Object>();
        for (var field : fields.split(",")) {
            var name = field.trim();
            if (name.isEmpty()) continue;
            var value = state.getOrDefault(name, MISSING_FIELD);
            if (value != MISSING_FIELD) selected.put(name, value);
        }
        return selected;
    }

    private final DatasetService datasetService;
    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private GetSessionStateTool(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
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
        if (registry.resolve(datasetId) == null) {
            return ToolCallResult.failed("access denied to dataset: " + datasetId);
        }
        var dataset = datasetService.get(datasetId);
        if (dataset == null) {
            return ToolCallResult.failed("dataset not found: " + datasetId);
        }
        if (DatasetService.resolveType(dataset) != DatasetType.SESSION) {
            return ToolCallResult.failed("not a session dataset, use dataset record tools instead: " + datasetId);
        }

        var record = recordService.queryBySession(datasetId, context.getSessionId()).orElse(null);
        var response = new LinkedHashMap<String, Object>();
        if (record != null) {
            var state = JsonUtil.toMap(record.data);
            var fieldsStr = getStringValue(args, "fields");
            if (fieldsStr != null && !fieldsStr.isBlank()) {
                response.put("state", selectFields(state, fieldsStr));
            } else {
                response.put("state", state);
            }
        } else {
            response.put("state", null);
        }
        response.put("dataset_id", datasetId);
        response.put("session_id", context.getSessionId());
        return ToolCallResult.completed(JsonUtil.toJson(response));
    }
}
