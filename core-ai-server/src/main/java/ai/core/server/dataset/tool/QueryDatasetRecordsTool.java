package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.Dataset;
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

/**
 * @author stephen
 */
public final class QueryDatasetRecordsTool extends ToolCall {
    public static final String TOOL_NAME = "query_dataset_records";

    public static QueryDatasetRecordsTool create(String datasetId, DatasetRecordService recordService, Dataset dataset) {
        var tool = new QueryDatasetRecordsTool(datasetId, recordService);
        tool.setName(TOOL_NAME);
        tool.setDescription(buildDescription(dataset));
        tool.setParameters(parameters());
        tool.setNeedAuth(false);
        tool.setDirectReturn(false);
        tool.setLlmVisible(true);
        tool.setDiscoverable(false);
        return tool;
    }

    @SuppressWarnings({"PMD.ConsecutiveLiteralAppends", "PMD.AppendCharacterWithChar", "PMD.ConsecutiveAppendsShouldReuse", "PMD.UseLocaleWithCaseConversions"})
    private static String buildDescription(Dataset dataset) {
        var sb = new StringBuilder(320);
        sb.append("Query records from the dataset \"").append(dataset != null ? dataset.name : "unknown").append("\".\n");
        sb.append("Use this tool to search, filter, and retrieve records by time range and field projection.\n").append("The dataset is automatically determined — do NOT specify a dataset_id.\n");
        if (dataset != null && dataset.schema != null && !dataset.schema.isEmpty()) {
            sb.append("\nSchema fields:\n");
            for (var field : dataset.schema) {
                sb.append("  - ").append(field.name).append(" (").append(field.type.name().toLowerCase()).append(")");
                if (field.label != null) sb.append(": ").append(field.label);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "fields", "Comma-separated field names to include in results. If not specified, all fields are returned."),
            ToolCallParameters.ParamSpec.of(String.class, "from", "ISO 8601 datetime string for the lower bound of run_started_at. e.g. 2026-01-01T00:00:00Z"),
            ToolCallParameters.ParamSpec.of(String.class, "to", "ISO 8601 datetime string for the upper bound of run_started_at."),
            ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Maximum number of records to return. Default 100."),
            ToolCallParameters.ParamSpec.of(Integer.class, "offset", "Number of records to skip for pagination. Default 0.")
        );
    }

    private final String datasetId;
    private final DatasetRecordService recordService;

    private QueryDatasetRecordsTool(String datasetId, DatasetRecordService recordService) {
        this.datasetId = datasetId;
        this.recordService = recordService;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
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
