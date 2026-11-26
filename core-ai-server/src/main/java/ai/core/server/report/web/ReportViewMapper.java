package ai.core.server.report.web;

import ai.core.api.server.report.ReportComponentDataView;
import ai.core.api.server.report.ReportDataView;
import ai.core.api.server.report.ReportPinView;
import ai.core.api.server.report.ReportView;
import ai.core.server.report.application.HomeReportService;
import ai.core.server.report.application.ReportService;
import ai.core.server.report.domain.HomeReportPin;
import ai.core.server.report.domain.ReportData;
import ai.core.server.report.domain.ReportDataBundle;
import ai.core.server.report.domain.ReportDefinition;
import ai.core.server.report.domain.ReportSummary;
import ai.core.utils.JsonUtil;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public final class ReportViewMapper {
    public static ReportDataView data(ReportData data) {
        var view = new ReportDataView();
        fill(view, data);
        return view;
    }

    public static ReportDataView bundle(ReportDataBundle bundle) {
        var view = new ReportDataView();
        view.reportId = bundle.reportId;
        view.reportRevision = bundle.reportRevision;
        view.specVersion = bundle.specVersion;
        if (!bundle.components.isEmpty()) fill(view, bundle.components.get(0).data);
        view.components = bundle.components.stream().map(ReportViewMapper::component).toList();
        return view;
    }

    private static ReportComponentDataView component(ReportDataBundle.Component component) {
        var view = new ReportComponentDataView();
        view.id = component.id;
        view.title = component.title;
        view.displayType = component.displayType;
        fill(view, component.data);
        return view;
    }

    private static void fill(ReportDataView view, ReportData data) {
        if (data == null) return;
        view.reportId = data.reportId;
        view.reportRevision = data.reportRevision;
        view.specVersion = data.specVersion;
        view.status = data.status;
        view.fromInclusive = data.fromInclusive;
        view.toExclusive = data.toExclusive;
        view.timezone = data.timezone;
        view.columnsJson = JsonUtil.toJson(data.columns);
        view.rowsJson = JsonUtil.toJson(data.rows);
        view.matchedRecordCount = data.matchedRecordCount;
        view.invalidValueCount = data.invalidValueCount;
        view.sourceTime = data.sourceTime;
        view.sourceUpdatedAt = data.sourceUpdatedAt;
        view.evaluatedAt = data.evaluatedAt;
        view.freshnessStatus = data.freshnessStatus;
        view.hasMore = data.hasMore;
    }

    private static void fill(ReportComponentDataView view, ReportData data) {
        if (data == null) return;
        view.status = data.status;
        view.fromInclusive = data.fromInclusive;
        view.toExclusive = data.toExclusive;
        view.timezone = data.timezone;
        view.columnsJson = JsonUtil.toJson(data.columns);
        view.rowsJson = JsonUtil.toJson(data.rows);
        view.matchedRecordCount = data.matchedRecordCount;
        view.invalidValueCount = data.invalidValueCount;
        view.sourceTime = data.sourceTime;
        view.sourceUpdatedAt = data.sourceUpdatedAt;
        view.evaluatedAt = data.evaluatedAt;
        view.freshnessStatus = data.freshnessStatus;
        view.hasMore = data.hasMore;
    }

    public static ReportView report(ReportDefinition report, String datasetName, HomeReportPin pin) {
        var view = new ReportView();
        view.id = report.id;
        view.title = report.title;
        view.description = report.description;
        view.createdBy = report.userId;
        view.revision = report.revision;
        view.specVersion = report.specVersion;
        view.engineVersion = report.engineVersion;
        view.datasetId = report.datasetId;
        view.datasetName = datasetName;
        view.displayType = ReportService.displayType(report.spec);
        view.specJson = report.specJson;
        view.pinId = pin != null ? pin.id : null;
        view.pinWidth = pin != null ? pin.width : null;
        view.createdAt = time(report.createdAt);
        view.updatedAt = time(report.updatedAt);
        return view;
    }

    public static ReportView report(ReportSummary summary) {
        var view = new ReportView();
        view.id = summary.id;
        view.title = summary.title;
        view.createdBy = summary.userId;
        view.revision = summary.revision;
        view.datasetId = summary.datasetId;
        view.datasetName = summary.datasetName;
        view.displayType = summary.displayType;
        view.pinId = summary.pinId;
        view.pinWidth = summary.pinWidth;
        view.updatedAt = time(summary.updatedAt);
        return view;
    }

    public static ReportPinView pin(HomeReportPin pin, ReportSummary summary) {
        var view = new ReportPinView();
        view.id = pin.id;
        view.reportId = pin.reportId;
        view.position = pin.position;
        view.width = pin.width;
        view.title = summary != null ? summary.title : null;
        view.displayType = summary != null ? summary.displayType : null;
        view.datasetId = summary != null ? summary.datasetId : null;
        view.reportRevision = summary != null ? summary.revision : null;
        view.updatedAt = summary != null ? time(summary.updatedAt) : time(pin.updatedAt);
        return view;
    }

    public static ReportPinView pin(HomeReportService.PinData item) {
        return pin(item.pin, item.summary);
    }

    private static String time(ZonedDateTime value) {
        return value != null ? value.toInstant().toString() : null;
    }

    private ReportViewMapper() {
    }
}
