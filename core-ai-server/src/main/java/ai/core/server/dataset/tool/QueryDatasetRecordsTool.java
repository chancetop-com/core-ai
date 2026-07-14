package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
public final class QueryDatasetRecordsTool extends ToolCall {
    public static final String TOOL_NAME = "query_dataset_records";

    public static QueryDatasetRecordsTool create(DatasetService datasetService, DatasetRecordService recordService, DatasetAccessRegistry registry) {
        var tool = new QueryDatasetRecordsTool(recordService, registry);
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
        return "Query records from a dataset by dataset_id.\nUse this tool to search, filter, and retrieve records by time range and field projection.\n"
                + buildAvailableDatasetsSection(datasetService, registry);
    }

    static String buildAvailableDatasetsSection(DatasetService datasetService, DatasetAccessRegistry registry) {
        var sb = new StringBuilder(256);
        sb.append("\nAvailable datasets (specify dataset_id to choose):\n");
        for (var entry : registry.allowedDatasets().entrySet()) {
            var dataset = datasetService.get(entry.getKey());
            if (dataset == null) continue;
            sb.append("- \"").append(dataset.name).append("\" (id: ").append(entry.getKey())
              .append(", permission: ").append(entry.getValue().name()).append(')');
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

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "dataset_id", "The ID of the dataset to query. Required — choose from available datasets listed above.").required(),
            ToolCallParameters.ParamSpec.of(String.class, "fields", "Comma-separated field names to include in results. If not specified, all fields are returned."),
            ToolCallParameters.ParamSpec.of(String.class, "from", "ISO 8601 datetime string for the lower bound of run_started_at. e.g. 2026-01-01T00:00:00Z"),
            ToolCallParameters.ParamSpec.of(String.class, "to", "ISO 8601 datetime string for the upper bound of run_started_at."),
            ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Maximum number of records to return. Default 100."),
            ToolCallParameters.ParamSpec.of(Integer.class, "offset", "Number of records to skip for pagination. Default 0.")
        );
    }

    private final DatasetRecordService recordService;
    private final DatasetAccessRegistry registry;

    private QueryDatasetRecordsTool(DatasetRecordService recordService, DatasetAccessRegistry registry) {
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
        if (registry.resolve(datasetId) == null) {
            return ToolCallResult.failed("access denied to dataset: " + datasetId);
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

        List<String> fields = fieldsStr != null ? List.of(fieldsStr.split(",")) : null;

        var result = recordService.query(new DatasetRecordService.QueryRequest(datasetId, from, to, fields, limit, offset, null));
        var response = new LinkedHashMap<String, Object>();
        response.put("records", result.records().stream().map(r -> {
            var record = new LinkedHashMap<String, Object>();
            record.put("id", r.id);
            record.put("run_id", r.runId);
            record.put("agent_id", r.agentId);
            record.put("run_started_at", r.runStartedAt != null ? r.runStartedAt.format(DateTimeFormatter.ISO_DATE_TIME) : null);
            record.put("data", r.data != null ? JsonUtil.toMap(r.data) : null);
            return record;
        }).toList());
        response.put("total", result.total());
        response.put("dataset_id", datasetId);
        return ToolCallResult.completed(JsonUtil.toJson(response));
    }
}
