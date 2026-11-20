package ai.core.server.selfharness;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetService;
import ai.core.server.report.application.ReportException;
import ai.core.server.report.application.ReportService;
import ai.core.server.report.domain.ReportData;
import ai.core.server.report.domain.ReportDefinition;
import ai.core.server.report.domain.ReportSummary;
import ai.core.server.session.SessionDatasetHelper;
import ai.core.session.CustomEventEmitter;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.json.JSON;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dataset report operations of the self-harness dataset group. Owner scoping and RBAC are enforced
 * inside {@link ReportService}; this dispatcher only maps tool arguments onto that service and keeps
 * the report payload shape aligned with the chat report tools.
 *
 * @author core-ai
 */
public class SelfHarnessReportDispatcher {
    private static final int PREVIEW_ROW_LIMIT = 20;

    @Inject
    DatasetService datasetService;

    Object dispatch(String name, String args, String userId, ExecutionContext context) {
        var reportService = SessionDatasetHelper.reportService();
        if (reportService == null) {
            throw new IllegalStateException("dataset reports are not enabled on this server, so report tools cannot be used");
        }
        try {
            return switch (name) {
                case "list_dataset_reports" -> list(reportService, args, userId);
                case "get_dataset_report" -> get(reportService, args, userId);
                case "preview_dataset_report" -> preview(reportService, args, userId);
                case "create_dataset_report" -> create(reportService, args, userId, context);
                case "update_dataset_report" -> update(reportService, args, userId, context);
                default -> throw new IllegalArgumentException("Unknown dataset report operation: " + name);
            };
        } catch (ReportException e) {
            throw new IllegalArgumentException(errorMessage(e), e);
        }
    }

    private Object list(ReportService reportService, String args, String userId) {
        var params = arguments(args);
        var datasetId = stringParam(params, "dataset_id");
        var offset = Math.max(intParam(params, "offset", 0), 0);
        var limit = Math.min(Math.max(intParam(params, "limit", 20), 1), 100);
        var reports = reportService.list(userId, 0, ReportService.MAX_REPORTS_PER_USER);
        var filtered = datasetId == null ? reports : reports.stream().filter(report -> datasetId.equals(report.datasetId)).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("reports", filtered.stream().skip(offset).limit(limit).map(this::summaryRow).toList());
        result.put("total", filtered.size());
        return result;
    }

    private Object get(ReportService reportService, String args, String userId) {
        var params = arguments(args);
        return reportPayload(reportService.get(userId, requiredString(params, "report_id")));
    }

    private Object preview(ReportService reportService, String args, String userId) {
        var params = arguments(args);
        var specJson = requiredString(params, "spec_json");
        var title = requiredString(params, "title");
        return dataPayload(reportService.preview(userId, title, stringParam(params, "description"), specJson));
    }

    private Object create(ReportService reportService, String args, String userId, ExecutionContext context) {
        var params = arguments(args);
        var specJson = requiredString(params, "spec_json");
        var title = requiredString(params, "title");
        var report = reportService.create(userId, sessionId(context), null, title, stringParam(params, "description"), specJson);
        emit(context, "report_created", report);
        return reportPayload(report);
    }

    private Object update(ReportService reportService, String args, String userId, ExecutionContext context) {
        var params = arguments(args);
        var reportId = requiredString(params, "report_id");
        var revision = longParam(params, "expected_revision");
        if (revision == null) throw new IllegalArgumentException("expected_revision is required");
        var specJson = requiredString(params, "spec_json");
        var title = requiredString(params, "title");
        var report = reportService.update(userId, sessionId(context), null, reportId, revision,
                new ReportService.ReportDraft(title, stringParam(params, "description"), specJson));
        emit(context, "report_updated", report);
        return reportPayload(report);
    }

    private String sessionId(ExecutionContext context) {
        return context != null ? context.getSessionId() : null;
    }

    private Map<String, Object> summaryRow(ReportSummary summary) {
        var row = new LinkedHashMap<String, Object>();
        row.put("report_id", summary.id);
        row.put("title", summary.title);
        row.put("revision", summary.revision);
        row.put("dataset_id", summary.datasetId);
        row.put("dataset_name", summary.datasetName);
        row.put("display_type", summary.displayType);
        row.put("pinned", summary.pinId != null);
        row.put("updated_at", summary.updatedAt != null ? summary.updatedAt.toInstant().toString() : null);
        return row;
    }

    private Map<String, Object> reportPayload(ReportDefinition report) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("report_id", report.id);
        payload.put("revision", report.revision);
        payload.put("title", report.title);
        payload.put("description", report.description);
        payload.put("dataset_id", report.datasetId);
        payload.put("dataset_name", datasetName(report.datasetId));
        payload.put("spec_version", report.specVersion);
        payload.put("engine_version", report.engineVersion);
        payload.put("spec_json", report.specJson);
        payload.put("url", "/datasets/reports/" + report.id);
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
        payload.put("rows", data.rows.stream().limit(PREVIEW_ROW_LIMIT).toList());
        payload.put("matched_record_count", data.matchedRecordCount);
        payload.put("invalid_value_count", data.invalidValueCount);
        payload.put("source_time", data.sourceTime);
        payload.put("evaluated_at", data.evaluatedAt);
        payload.put("freshness_status", data.freshnessStatus);
        payload.put("has_more", data.hasMore);
        return payload;
    }

    private String datasetName(String datasetId) {
        var dataset = datasetService.get(datasetId);
        return dataset != null ? dataset.name : null;
    }

    private void emit(ExecutionContext context, String name, ReportDefinition report) {
        var value = context != null ? context.getCustomVariables().get(CustomEventEmitter.CONTEXT_KEY) : null;
        if (!(value instanceof CustomEventEmitter emitter)) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("report_id", report.id);
        payload.put("revision", report.revision);
        payload.put("title", report.title);
        emitter.emit(name, JsonUtil.toJson(payload), null, context.getCurrentToolCallId());
    }

    private String errorMessage(ReportException e) {
        var message = e.errorCode() + ": " + e.getMessage();
        return e.extraJson() != null ? message + " " + e.extraJson() : message;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> arguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        return JSON.fromJSON(Map.class, arguments);
    }

    private String requiredString(Map<String, Object> params, String name) {
        var value = stringParam(params, name);
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String stringParam(Map<String, Object> params, String name) {
        var value = params.get(name);
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private Integer intParam(Map<String, Object> params, String name, int defaultValue) {
        var value = params.get(name);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private Long longParam(Map<String, Object> params, String name) {
        var value = params.get(name);
        return value instanceof Number number ? number.longValue() : null;
    }
}
