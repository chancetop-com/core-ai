package ai.core.server.report.application;

import ai.core.server.dataset.DatasetService;
import ai.core.server.report.domain.HomeReportPin;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportData;
import ai.core.server.report.domain.ReportSummary;
import ai.core.server.report.store.PgDatasetReportStore;
import ai.core.server.report.store.PgHomeReportLayoutStore;
import core.framework.db.Database;
import core.framework.db.Transaction;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Home report layout/pin application service. Pins are a separate aggregate from report definitions;
 * only user-facing APIs call this service.
 *
 * @author core-ai
 */
public class HomeReportService {
    @Inject
    Database database;

    @Inject
    PgHomeReportLayoutStore layoutStore;

    @Inject
    PgDatasetReportStore reportStore;

    @Inject
    ReportService reportService;

    @Inject
    ReportQueryService queryService;

    @Inject
    ReportAccessPolicy accessPolicy;

    @Inject
    DatasetService datasetService;

    public LayoutData layout(String userId) {
        accessPolicy.requirePin(userId);
        layoutStore.ensure(userId);
        var layout = layoutStore.get(userId).orElseThrow(ReportException::notFound);
        var result = new LayoutData(layout.revision, new ArrayList<>());
        for (var pin : layoutStore.pins(userId)) {
            var report = reportStore.getOwned(pin.reportId, userId).orElse(null);
            if (report == null) continue;
            var item = new PinData();
            item.pin = pin;
            item.summary = reportService.summary(report, pin);
            result.pins.add(item);
        }
        return result;
    }

    public HomeReportPin pin(String userId, String reportId, String width, long expectedLayoutRevision) {
        accessPolicy.requirePin(userId);
        var effectiveWidth = normalizeWidth(width);
        try (Transaction transaction = database.beginTransaction()) {
            layoutStore.ensure(userId);
            var layout = layoutStore.lock(userId).orElseThrow(ReportException::notFound);
            if (layout.revision != expectedLayoutRevision) {
                throw ReportException.conflict("LAYOUT_REVISION_CONFLICT", "layout revision changed: " + layout.revision);
            }
            var report = reportStore.getOwned(reportId, userId).orElseThrow(ReportException::notFound);
            var existing = layoutStore.pinByReport(userId, reportId).orElse(null);
            if (existing != null) {
                transaction.commit();
                return existing;
            }
            if (layoutStore.countPins(userId) >= PgHomeReportLayoutStore.MAX_PINS) {
                throw ReportException.limit("PIN_LIMIT_REACHED", "pin limit reached: " + PgHomeReportLayoutStore.MAX_PINS);
            }
            var now = ZonedDateTime.now();
            var pin = new HomeReportPin();
            pin.id = UUID.randomUUID().toString();
            pin.userId = userId;
            pin.reportId = reportId;
            pin.position = layoutStore.nextPosition(userId);
            pin.width = effectiveWidth;
            pin.createdAt = now;
            pin.updatedAt = now;
            layoutStore.insertPin(pin);
            layoutStore.bumpRevision(userId, layout.revision);
            reportService.audit(report, ReportConstants.ACTION_PINNED, null, null, report.revision, report.specJson);
            transaction.commit();
            return pin;
        }
    }

    public void unpin(String userId, String pinId, long expectedLayoutRevision) {
        accessPolicy.requirePin(userId);
        try (Transaction transaction = database.beginTransaction()) {
            layoutStore.ensure(userId);
            var layout = layoutStore.lock(userId).orElseThrow(ReportException::notFound);
            if (layout.revision != expectedLayoutRevision) {
                throw ReportException.conflict("LAYOUT_REVISION_CONFLICT", "layout revision changed: " + layout.revision);
            }
            var pin = layoutStore.pin(userId, pinId).orElseThrow(ReportException::notFound);
            var report = reportStore.getOwned(pin.reportId, userId).orElse(null);
            layoutStore.deletePin(userId, pinId);
            layoutStore.bumpRevision(userId, layout.revision);
            if (report != null) {
                reportService.audit(report, ReportConstants.ACTION_UNPINNED, null, null, report.revision, report.specJson);
            }
            transaction.commit();
        }
    }

    public void reorder(String userId, List<ReorderItem> items, long expectedLayoutRevision) {
        accessPolicy.requirePin(userId);
        if (items == null) throw ReportException.badRequest("INVALID_LAYOUT", "items are required");
        if (items.size() > PgHomeReportLayoutStore.MAX_PINS) {
            throw ReportException.limit("PIN_LIMIT_REACHED", "pin limit reached: " + PgHomeReportLayoutStore.MAX_PINS);
        }
        try (Transaction transaction = database.beginTransaction()) {
            layoutStore.ensure(userId);
            var layout = layoutStore.lock(userId).orElseThrow(ReportException::notFound);
            if (layout.revision != expectedLayoutRevision) {
                throw ReportException.conflict("LAYOUT_REVISION_CONFLICT", "layout revision changed: " + layout.revision);
            }
            var current = layoutStore.pins(userId);
            var currentIds = new HashSet<String>();
            for (var pin : current) currentIds.add(pin.id);
            var seen = new HashSet<String>();
            for (var item : items) {
                if (item == null || item.pinId == null || !seen.add(item.pinId) || !currentIds.contains(item.pinId)) {
                    throw ReportException.badRequest("INVALID_LAYOUT", "reorder must contain each current pin exactly once");
                }
            }
            if (seen.size() != currentIds.size()) {
                throw ReportException.badRequest("INVALID_LAYOUT", "reorder must contain every current pin");
            }
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                layoutStore.updatePosition(userId, item.pinId, i);
                if (item.width != null) layoutStore.updateWidth(userId, item.pinId, normalizeWidth(item.width));
                var pin = layoutStore.pin(userId, item.pinId).orElse(null);
                if (pin != null) {
                    var report = reportStore.getOwned(pin.reportId, userId).orElse(null);
                    if (report != null) {
                        reportService.audit(report, ReportConstants.ACTION_REORDERED, null, null, report.revision, report.specJson);
                    }
                }
            }
            layoutStore.bumpRevision(userId, layout.revision);
            transaction.commit();
        }
    }

    public List<PinData> data(String userId, List<String> pinIds, ReportQueryService.RangeOverride override, int pageSize, int offset) {
        accessPolicy.requirePin(userId);
        if (pinIds == null || pinIds.isEmpty()) return List.of();
        if (pinIds.size() > 8) throw ReportException.badRequest("TOO_MANY_PINS", "at most 8 pins per data request");
        var result = new ArrayList<PinData>(pinIds.size());
        for (var pinId : pinIds) {
            var item = new PinData();
            var pin = layoutStore.pin(userId, pinId).orElse(null);
            if (pin == null) {
                item.errorCode = "REPORT_NOT_FOUND";
                item.errorMessage = "pin not found";
                result.add(item);
                continue;
            }
            item.pin = pin;
            var report = reportStore.getOwned(pin.reportId, userId).orElse(null);
            if (report == null) {
                item.errorCode = "REPORT_NOT_FOUND";
                item.errorMessage = "report not found";
                result.add(item);
                continue;
            }
            item.summary = reportService.summary(report, pin);
            try {
                var dataset = datasetService.get(report.datasetId);
                if (dataset == null) {
                    item.errorCode = "SOURCE_ACCESS_DENIED";
                    item.errorMessage = "dataset is not accessible";
                } else {
                    item.data = queryService.query(new ReportQueryService.QueryRequest(report.id, report.revision, report.specVersion,
                            report.spec, dataset, ZonedDateTime.now(), override, pageSize, offset));
                }
            } catch (ReportException e) {
                item.errorCode = e.errorCode();
                item.errorMessage = e.getMessage();
            } catch (RuntimeException e) {
                item.errorCode = "REPORT_QUERY_UNAVAILABLE";
                item.errorMessage = e.getMessage();
            }
            result.add(item);
        }
        return result;
    }

    private String normalizeWidth(String width) {
        if (width == null || width.isBlank()) return ReportConstants.WIDTH_HALF;
        if (!ReportConstants.WIDTH_HALF.equals(width) && !ReportConstants.WIDTH_FULL.equals(width)) {
            throw ReportException.badRequest("INVALID_WIDTH", "width must be HALF or FULL");
        }
        return width;
    }

    public record LayoutData(long revision, List<PinData> pins) {
    }

    public static class PinData {
        public HomeReportPin pin;
        public ReportSummary summary;
        public ReportData data;
        public String errorCode;
        public String errorMessage;
    }

    public record ReorderItem(String pinId, String width) {
    }
}
