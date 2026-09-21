package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetOpPayloads;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.DatasetType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.util.List;
import java.util.Map;

/**
 * Reads the session state document for the current session from a SESSION dataset.
 *
 * @author stephen
 */
public final class GetSessionStateTool extends ToolCall {
    public static final String TOOL_NAME = "get_session_state";

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
        var datasetRef = getStringValue(args, "dataset_id");
        if (datasetRef == null || datasetRef.isBlank()) {
            return ToolCallResult.failed("dataset_id is required");
        }
        if (context == null || context.getSessionId() == null) {
            return ToolCallResult.failed("session context required");
        }
        var ambiguous = registry.ambiguousMessage(datasetRef);
        if (ambiguous != null) {
            return ToolCallResult.failed(ambiguous);
        }
        var datasetId = registry.resolveId(datasetRef);
        if (datasetId == null) {
            return ToolCallResult.failed("access denied to dataset: " + datasetRef);
        }
        var dataset = datasetService.get(datasetId);
        if (dataset == null) {
            return ToolCallResult.failed("dataset not found: " + datasetRef);
        }
        if (DatasetService.resolveType(dataset) != DatasetType.SESSION) {
            return ToolCallResult.failed("not a session dataset, use dataset record tools instead: " + datasetRef);
        }

        var record = recordService.queryBySession(datasetId, context.getSessionId()).orElse(null);
        Map<String, Object> state = null;
        if (record != null) {
            state = JsonUtil.toMap(record.data);
            var fieldsStr = getStringValue(args, "fields");
            if (fieldsStr != null && !fieldsStr.isBlank()) state = DatasetOpPayloads.selectStateFields(state, fieldsStr);
        }
        return ToolCallResult.completed(DatasetOpPayloads.stateRead(state, datasetId, context.getSessionId()));
    }
}
