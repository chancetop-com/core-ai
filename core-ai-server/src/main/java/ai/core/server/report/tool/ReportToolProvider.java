package ai.core.server.report.tool;

import ai.core.agent.ExecutionContext;
import ai.core.session.CustomEventEmitter;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.report.application.ReportException;
import ai.core.server.report.application.ReportService;
import ai.core.server.report.domain.ReportData;
import ai.core.server.report.domain.ReportDefinition;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import ai.core.tool.registry.ToolProvider;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report tools scoped to one interactive session's dataset bindings. No pin/delete/reorder tool exists:
 * those actions stay in the report management page.
 *
 * @author core-ai
 */
public class ReportToolProvider implements ToolProvider {
    public static final String ID = "report";

    private final DatasetService datasetService;
    private final ReportService reportService;
    private final DatasetAccessRegistry registry;
    private final Map<String, ToolCall> tools;

    public ReportToolProvider(DatasetService datasetService, ReportService reportService, DatasetAccessRegistry registry) {
        this.datasetService = datasetService;
        this.reportService = reportService;
        this.registry = registry;
        var map = new LinkedHashMap<String, ToolCall>();
        map.put("list_report_sources", listSources());
        map.put("list_dataset_reports", listReports());
        map.put("get_dataset_report", getReport());
        map.put("preview_dataset_report", preview());
        map.put("create_dataset_report", create());
        map.put("update_dataset_report", update());
        this.tools = Map.copyOf(map);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 11;
    }

    @Override
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.ONCE;
    }

    @Override
    public Map<String, ToolCall> provide() {
        return tools;
    }

    private ToolCall listSources() {
        return tool("list_report_sources",
                "List GENERAL datasets this chat session may use as report sources. Use the dataset id, not the name, in spec_json.",
                List.of(), (arguments, context) -> {
                    var result = new ArrayList<Map<String, Object>>();
                    for (var binding : registry.bindings(datasetService)) {
                        if (binding.type() != ai.core.server.domain.DatasetType.GENERAL) continue;
                        var item = new LinkedHashMap<String, Object>();
                        item.put("dataset_id", binding.datasetId());
                        item.put("name", binding.name());
                        item.put("permission", binding.permission().name());
                        item.put("description", binding.description());
                        item.put("schema", binding.schema().stream().map(field -> Map.of(
                                "name", field.name,
                                "type", field.type != null ? field.type.name() : "STRING",
                                "label", field.label != null ? field.label : field.name)).toList());
                        result.add(item);
                    }
                    return ToolCallResult.completed(JsonUtil.toJson(Map.of("sources", result)));
                });
    }

    private ToolCall listReports() {
        return tool("list_dataset_reports",
                "List the current user's saved dataset reports. Use get_dataset_report before updating one.",
                List.of(), (arguments, context) -> {
                    var userId = requireUser(context);
                    var reports = reportService.list(userId, 0, 50);
                    return ToolCallResult.completed(JsonUtil.toJson(Map.of("reports", reports)));
                });
    }

    private ToolCall getReport() {
        return tool("get_dataset_report",
                "Read one saved report definition, including its current revision and spec_json.",
                ToolCallParameters.of(ToolCallParameters.ParamSpec.of(String.class, "report_id", "Saved report id.").required()),
                (arguments, context) -> {
                    var args = arguments(arguments);
                    var reportId = ToolCall.getStringValue(args, "report_id");
                    if (reportId == null || reportId.isBlank()) return ToolCallResult.failed("report_id is required");
                    var report = reportService.get(requireUser(context), reportId);
                    return ToolCallResult.completed(JsonUtil.toJson(reportPayload(report)));
                });
    }

    private ToolCall preview() {
        return tool("preview_dataset_report",
                "Validate and preview a report spec without saving it. Use this when the user is still exploring.",
                reportSpecParameters(), (arguments, context) -> {
                    var args = arguments(arguments);
                    var specJson = requiredSpec(args);
                    if (specJson == null) return ToolCallResult.failed("spec_json is required");
                    requireSourceBinding(specJson);
                    var data = reportService.preview(requireUser(context), ToolCall.getStringValue(args, "title"),
                            ToolCall.getStringValue(args, "description"), specJson);
                    return ToolCallResult.completed(JsonUtil.toJson(dataPayload(data)));
                });
    }

    private ToolCall create() {
        return tool("create_dataset_report",
                "Create and save a report from spec_json. The report appears in /reports; it is not pinned to the home page.",
                reportSpecParameters(), (arguments, context) -> {
                    var args = arguments(arguments);
                    var specJson = requiredSpec(args);
                    if (specJson == null) return ToolCallResult.failed("spec_json is required");
                    var datasetId = sourceDatasetId(specJson);
                    if (!registry.allowedDatasets().containsKey(datasetId)) {
                        return ToolCallResult.failed("access denied to dataset: " + datasetId);
                    }
                    var report = reportService.create(requireUser(context), context.getSessionId(), null,
                            ToolCall.getStringValue(args, "title"), ToolCall.getStringValue(args, "description"), specJson);
                    emit(context, "report_created", report);
                    return ToolCallResult.completed(JsonUtil.toJson(reportPayload(report)));
                });
    }

    private ToolCall update() {
        return tool("update_dataset_report",
                "Update a saved report. Use get_dataset_report first and pass its current revision; a mismatch is rejected.",
                ToolCallParameters.of(
                        ToolCallParameters.ParamSpec.of(String.class, "report_id", "Saved report id.").required(),
                        ToolCallParameters.ParamSpec.of(Integer.class, "expected_revision", "Current report revision from get_dataset_report.").required(),
                        ToolCallParameters.ParamSpec.of(String.class, "title", "New title.").required(),
                        ToolCallParameters.ParamSpec.of(String.class, "description", "New description."),
                        ToolCallParameters.ParamSpec.of(String.class, "spec_json", "New spec_version=1 JSON string.").required()),
                (arguments, context) -> {
                    var args = arguments(arguments);
                    var reportId = ToolCall.getStringValue(args, "report_id");
                    var revision = args.get("expected_revision") instanceof Number number ? number.longValue() : null;
                    var specJson = requiredSpec(args);
                    if (reportId == null || revision == null || specJson == null) {
                        return ToolCallResult.failed("report_id, expected_revision and spec_json are required");
                    }
                    var report = reportService.update(requireUser(context), context.getSessionId(), null, reportId, revision,
                            new ReportService.ReportDraft(ToolCall.getStringValue(args, "title"),
                                    ToolCall.getStringValue(args, "description"), specJson));
                    emit(context, "report_updated", report);
                    return ToolCallResult.completed(JsonUtil.toJson(reportPayload(report)));
                });
    }

    private List<ToolCallParameter> reportSpecParameters() {
        return ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "title", "Report title, at most 120 characters.").required(),
                ToolCallParameters.ParamSpec.of(String.class, "description", "Optional description, at most 2000 characters."),
                ToolCallParameters.ParamSpec.of(String.class, "spec_json", "ReportSpec JSON string, spec_version=1. Use data.<field> paths from list_report_sources.").required());
    }

    private void requireSourceBinding(String specJson) {
        var datasetId = sourceDatasetId(specJson);
        if (datasetId == null || !registry.allowedDatasets().containsKey(datasetId)) {
            throw ReportException.forbidden("SOURCE_ACCESS_DENIED", "dataset is not bound to this session: " + datasetId);
        }
    }

    private String sourceDatasetId(String specJson) {
        try {
            var spec = JsonUtil.fromJson(ai.core.server.report.domain.ReportSpec.class, specJson);
            return spec.source != null ? spec.source.datasetId : null;
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private Map<String, Object> arguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        return JsonUtil.toMap(arguments);
    }

    private String requiredSpec(Map<String, Object> args) {
        var spec = ToolCall.getStringValue(args, "spec_json");
        return spec != null && !spec.isBlank() ? spec : null;
    }

    private String requireUser(ExecutionContext context) {
        var userId = context.getUserId();
        if (userId == null || userId.isBlank()) throw ReportException.forbidden("AUTH_REQUIRED", "report tools require a user context");
        return userId;
    }

    private void emit(ExecutionContext context, String name, ReportDefinition report) {
        var value = context != null ? context.getCustomVariables().get(CustomEventEmitter.CONTEXT_KEY) : null;
        var emitter = value instanceof CustomEventEmitter customEventEmitter ? customEventEmitter : null;
        if (emitter == null) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("report_id", report.id);
        payload.put("revision", report.revision);
        payload.put("title", report.title);
        emitter.emit(name, JsonUtil.toJson(payload), null, context.getCurrentToolCallId());
    }

    private Map<String, Object> reportPayload(ReportDefinition report) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("report_id", report.id);
        payload.put("revision", report.revision);
        payload.put("title", report.title);
        payload.put("description", report.description);
        payload.put("dataset_id", report.datasetId);
        payload.put("spec_version", report.specVersion);
        payload.put("engine_version", report.engineVersion);
        payload.put("spec_json", report.specJson);
        payload.put("url", "/reports/" + report.id);
        payload.put("updated_at", report.updatedAt != null ? report.updatedAt.toInstant().toString() : null);
        return payload;
    }

    private Map<String, Object> dataPayload(ReportData data) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", data.status);
        payload.put("report_id", data.reportId);
        payload.put("from_inclusive", data.fromInclusive);
        payload.put("to_exclusive", data.toExclusive);
        payload.put("timezone", data.timezone);
        payload.put("columns", data.columns);
        payload.put("rows", data.rows.stream().limit(20).toList());
        payload.put("matched_record_count", data.matchedRecordCount);
        payload.put("invalid_value_count", data.invalidValueCount);
        payload.put("source_time", data.sourceTime);
        payload.put("evaluated_at", data.evaluatedAt);
        payload.put("freshness_status", data.freshnessStatus);
        payload.put("has_more", data.hasMore);
        return payload;
    }

    private ToolCall tool(String name, String description, List<ToolCallParameter> parameters, ToolHandler handler) {
        return new HandlerTool(name, description, parameters, handler);
    }

    @FunctionalInterface
    private interface ToolHandler {
        ToolCallResult execute(String arguments, ExecutionContext context);
    }

    private static final class HandlerTool extends ToolCall {
        private final ToolHandler handler;

        private HandlerTool(String name, String description, List<ToolCallParameter> parameters, ToolHandler handler) {
            this.handler = handler;
            setName(name);
            setDescription(description);
            setParameters(parameters);
            setNeedAuth(Boolean.FALSE);
            setDirectReturn(Boolean.FALSE);
            setLlmVisible(Boolean.TRUE);
            setDiscoverable(Boolean.FALSE);
        }

        @Override
        public ToolCallResult execute(String arguments) {
            return execute(arguments, ExecutionContext.empty());
        }

        @Override
        public ToolCallResult execute(String arguments, ExecutionContext context) {
            try {
                return handler.execute(arguments, context);
            } catch (ReportException e) {
                return ToolCallResult.failed(e.errorCode() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                return ToolCallResult.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
    }
}
