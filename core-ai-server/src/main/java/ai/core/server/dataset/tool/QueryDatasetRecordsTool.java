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

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
public final class QueryDatasetRecordsTool extends ToolCall {
    public static final String TOOL_NAME = "query_dataset_records";

    public static QueryDatasetRecordsTool create(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new QueryDatasetRecordsTool(datasetService, recordService, registry);
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
        return "Query records from a dataset by dataset_id.\nUse this tool to search, filter, and retrieve records by time range, field-value conditions, and field projection.\n"
                + "Records are sorted by run_started_at desc, up to limit records per page (default 100); use offset to page through more results.\n"
                + buildAvailableDatasetsSection(datasetService, registry);
    }

    static String buildAvailableDatasetsSection(DatasetService datasetService, DatasetAccessRegistry registry) {
        var sb = new StringBuilder(256);
        sb.append("\nAvailable datasets (specify dataset_id to choose):\n");
        for (var entry : registry.allowedDatasets().entrySet()) {
            var dataset = datasetService.get(entry.getKey());
            if (dataset == null) continue;
            var type = DatasetService.resolveType(dataset);
            sb.append("- \"").append(dataset.name).append("\" (id: ").append(entry.getKey())
              .append(", type: ").append(type.name())
              .append(", permission: ").append(entry.getValue().name()).append(')');
            if (dataset.description != null && !dataset.description.isBlank()) {
                sb.append("\n  description: ").append(dataset.description);
            }
            if (dataset.schema != null && !dataset.schema.isEmpty()) {
                sb.append("\n  schema: ");
                var fieldDescs = dataset.schema.stream()
                    .map(f -> f.name + "(" + f.type.name().toLowerCase(Locale.ROOT) + ")")
                    .toList();
                sb.append(String.join(", ", fieldDescs));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** SESSION datasets hold per-session state and must only be reached through the state tools; the hub shares this
     * check so a script and the agent get the same dispatch error. */
    public static String sessionDatasetAccessError(DatasetService datasetService, String datasetId) {
        var dataset = datasetService.get(datasetId);
        if (dataset != null && DatasetService.resolveType(dataset) == DatasetType.SESSION) {
            return "session dataset is not accessible via dataset record tools, use get_session_state/set_session_state instead: " + datasetId;
        }
        return null;
    }

    // visible for testing; the parse and its message live in DatasetRecordService so the hub replays them
    static Map<String, Object> parseFilter(String filterStr) {
        return DatasetRecordService.parseFilter(filterStr);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the dataset to query. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(String.class, "filter", "JSON object with field-value conditions, e.g. {\"status\": \"done\"}. Records must match ALL conditions (AND, exact match). Keys are dataset schema field names; nested fields via dot path like \"meta.priority\"."),
            ToolCallParameters.ParamSpec.of(String.class, "fields", "Comma-separated schema field names to include in the data of each record. Metadata (id, run_id, agent_id, run_started_at) is always included."),
            ToolCallParameters.ParamSpec.of(String.class, "from", "ISO 8601 datetime string for the lower bound of run_started_at. e.g. 2026-01-01T00:00:00Z"),
            ToolCallParameters.ParamSpec.of(String.class, "to", "ISO 8601 datetime string for the upper bound of run_started_at."),
            ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Maximum number of records to return. Default 100."),
            ToolCallParameters.ParamSpec.of(Integer.class, "offset", "Number of records to skip for pagination. Default 0.")
        );
    }

    private final DatasetService datasetService;
    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private QueryDatasetRecordsTool(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
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
        if (datasetId == null) {
            return ToolCallResult.failed("access denied to dataset: " + datasetRef);
        }
        var sessionError = sessionDatasetAccessError(datasetService, datasetId);
        if (sessionError != null) {
            return ToolCallResult.failed(sessionError);
        }

        var fieldsStr = getStringValue(args, "fields");
        var fromStr = getStringValue(args, "from");
        var toStr = getStringValue(args, "to");
        var limit = args.get("limit") instanceof Number n ? n.intValue() : null;
        var offset = args.get("offset") instanceof Number n ? n.intValue() : null;

        ZonedDateTime from = null;
        ZonedDateTime to = null;
        try {
            if (fromStr != null) from = ZonedDateTime.parse(fromStr, DateTimeFormatter.ISO_DATE_TIME);
            if (toStr != null) to = ZonedDateTime.parse(toStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            return ToolCallResult.failed("invalid date format, use ISO 8601: " + e.getMessage());
        }

        Map<String, Object> filter;
        try {
            filter = parseFilter(getStringValue(args, "filter"));
        } catch (IllegalArgumentException e) {
            return ToolCallResult.failed(e.getMessage());
        }

        // a blank value means "no projection", matching get_session_state and the hub service
        List<String> fields = fieldsStr != null && !fieldsStr.isBlank() ? List.of(fieldsStr.split(",")) : null;

        var result = recordService.query(new DatasetRecordService.QueryRequest(datasetId, from, to, fields, limit, offset, null, filter));
        return ToolCallResult.completed(DatasetOpPayloads.records(datasetId, result));
    }
}
