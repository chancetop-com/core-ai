package ai.core.server.report.application;

import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.server.report.domain.HomeReportPin;
import ai.core.server.report.domain.ReportAuditEntry;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportData;
import ai.core.server.report.domain.ReportDataBundle;
import ai.core.server.report.domain.ReportDefinition;
import ai.core.server.report.domain.ReportSpec;
import ai.core.server.report.domain.ReportSummary;
import ai.core.server.report.store.PgDatasetReportStore;
import ai.core.server.report.store.PgHomeReportLayoutStore;
import ai.core.server.report.store.PgReportAuditStore;
import ai.core.server.report.store.PgReportLockRow;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.db.Transaction;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application service for DatasetReport: validation, preview, owner-scoped persistence and revision CAS.
 *
 * @author core-ai
 */
public class ReportService {
    public static final int MAX_REPORTS_PER_USER = 100;

    public static String displayType(ReportSpec spec) {
        if (spec == null) return null;
        if (spec.components != null && !spec.components.isEmpty()) {
            if (spec.components.size() > 1) return "DASHBOARD";
            var display = spec.components.get(0) != null ? spec.components.get(0).display : null;
            return display != null ? display.type : null;
        }
        return spec.display != null ? spec.display.type : null;
    }

    @Inject
    Database database;

    @Inject
    PgDatasetReportStore reportStore;

    @Inject
    PgHomeReportLayoutStore layoutStore;

    @Inject
    PgReportAuditStore auditStore;

    @Inject
    DatasetService datasetService;

    @Inject
    ReportSpecValidator validator;

    @Inject
    ReportQueryService queryService;

    @Inject
    ReportAccessPolicy accessPolicy;

    public ReportDefinition create(String userId, String sessionId, String agentId, String title, String description, String specJson) {
        accessPolicy.requireManage(userId);
        accessPolicy.requireDatasetView(userId);
        var draft = draftDataset(specJson);
        var dataset = requireDataset(draft.source.datasetId);
        var validated = validator.validate(title, description, specJson, dataset);
        var now = ZonedDateTime.now();
        previewComponents(validated.spec(), dataset, now);

        try (Transaction transaction = database.beginTransaction()) {
            lockUserReportQuota(userId);
            if (reportStore.countOwned(userId) >= MAX_REPORTS_PER_USER) {
                throw ReportException.limit("REPORT_LIMIT_REACHED", "report limit reached: " + MAX_REPORTS_PER_USER);
            }
            var report = new ReportDefinition();
            report.id = UUID.randomUUID().toString();
            report.userId = userId;
            report.title = title;
            report.description = description;
            report.revision = 1;
            report.specVersion = ReportConstants.SPEC_VERSION;
            report.engineVersion = ReportConstants.ENGINE_VERSION;
            report.spec = validated.spec();
            report.specJson = validated.specJson();
            report.datasetId = dataset.id;
            report.dependency = validated.dependency();
            report.dependencyJson = JsonUtil.toJson(validated.dependency());
            report.originSessionId = sessionId;
            report.originAgentId = agentId;
            report.createdAt = now;
            report.updatedAt = now;
            reportStore.insert(report);
            audit(report, ReportConstants.ACTION_CREATED, sessionId, agentId, 1, validated.specJson());
            transaction.commit();
            return report;
        }
    }

    public ReportDefinition update(String userId, String sessionId, String agentId, String reportId, long expectedRevision,
                                   ReportDraft draft) {
        accessPolicy.requireManage(userId);
        accessPolicy.requireDatasetView(userId);
        var existing = reportStore.getOwned(reportId, userId).orElseThrow(ReportException::notFound);
        if (existing.revision != expectedRevision) {
            throw ReportException.conflict("REPORT_REVISION_CONFLICT", "report revision changed: " + existing.revision);
        }
        var parsed = draftDataset(draft.specJson());
        var dataset = requireDataset(parsed.source.datasetId);
        var validated = validator.validate(draft.title(), draft.description(), draft.specJson(), dataset);
        var now = ZonedDateTime.now();
        previewComponents(validated.spec(), dataset, now);

        try (Transaction transaction = database.beginTransaction()) {
            var locked = reportStore.lockOwned(reportId, userId).orElseThrow(ReportException::notFound);
            if (locked.revision != expectedRevision) {
                throw ReportException.conflict("REPORT_REVISION_CONFLICT", "report revision changed: " + locked.revision);
            }
            locked.title = draft.title();
            locked.description = draft.description();
            locked.spec = validated.spec();
            locked.specJson = validated.specJson();
            locked.datasetId = dataset.id;
            locked.dependency = validated.dependency();
            locked.dependencyJson = JsonUtil.toJson(validated.dependency());
            locked.originSessionId = sessionId;
            locked.originAgentId = agentId;
            locked.updatedAt = now;
            if (!reportStore.update(locked, expectedRevision)) {
                throw ReportException.conflict("REPORT_REVISION_CONFLICT", "report revision changed");
            }
            audit(locked, ReportConstants.ACTION_UPDATED, sessionId, agentId, expectedRevision + 1, validated.specJson());
            transaction.commit();
        }
        return reportStore.getOwned(reportId, userId).orElseThrow(ReportException::notFound);
    }

    public void delete(String userId, String reportId, long expectedRevision, Long expectedLayoutRevision) {
        accessPolicy.requireManage(userId);
        try (Transaction transaction = database.beginTransaction()) {
            var report = reportStore.lockOwned(reportId, userId).orElseThrow(ReportException::notFound);
            if (report.revision != expectedRevision) {
                throw ReportException.conflict("REPORT_REVISION_CONFLICT", "report revision changed: " + report.revision);
            }
            var pin = layoutStore.pinByReport(userId, reportId).orElse(null);
            if (pin != null) {
                layoutStore.ensure(userId);
                var layout = layoutStore.lock(userId).orElseThrow(ReportException::notFound);
                if (expectedLayoutRevision != null && layout.revision != expectedLayoutRevision) {
                    throw ReportException.conflict("LAYOUT_REVISION_CONFLICT", "layout revision changed: " + layout.revision);
                }
                layoutStore.deletePinsByReport(userId, reportId);
                layoutStore.bumpRevision(userId, layout.revision);
            }
            if (!reportStore.delete(reportId, userId, expectedRevision)) {
                throw ReportException.conflict("REPORT_REVISION_CONFLICT", "report revision changed");
            }
            audit(report, ReportConstants.ACTION_DELETED, null, null, report.revision, report.specJson);
            transaction.commit();
        }
    }

    public ReportData preview(String userId, String title, String description, String specJson) {
        accessPolicy.requireManage(userId);
        accessPolicy.requireDatasetView(userId);
        var draft = draftDataset(specJson);
        var dataset = requireDataset(draft.source.datasetId);
        var validated = validator.validate(title, description, specJson, dataset);
        var components = components(validated.spec());
        if (components.isEmpty()) throw ReportException.badRequest("REPORT_INVALID_SPEC", "report has no components");
        return previewComponents(validated.spec(), dataset, ZonedDateTime.now()).get(0);
    }

    public ReportDefinition get(String userId, String reportId) {
        accessPolicy.requireView(userId);
        return reportStore.getOwned(reportId, userId).orElseThrow(ReportException::notFound);
    }

    public HomeReportPin pinFor(String userId, String reportId) {
        accessPolicy.requireView(userId);
        return layoutStore.pinByReport(userId, reportId).orElse(null);
    }

    public ReportData data(String userId, String reportId, ReportQueryService.RangeOverride override,
                           Map<String, Object> parameters, int pageSize, int offset) {
        var bundle = dataBundle(userId, reportId, override, parameters, pageSize, offset);
        return bundle.components.isEmpty() ? null : bundle.components.get(0).data;
    }

    public ReportDataBundle dataBundle(String userId, String reportId, ReportQueryService.RangeOverride override,
                                       Map<String, Object> parameters, int pageSize, int offset) {
        accessPolicy.requireView(userId);
        var report = reportStore.getOwned(reportId, userId).orElseThrow(ReportException::notFound);
        var dataset = datasetService.get(report.datasetId);
        if (dataset == null) throw ReportException.forbidden("SOURCE_ACCESS_DENIED", "dataset is not accessible");
        var bindings = ReportParameters.bindings(report.spec, parameters);
        var bundle = new ReportDataBundle();
        bundle.reportId = report.id;
        bundle.reportRevision = report.revision;
        bundle.specVersion = report.specVersion;
        var now = ZonedDateTime.now();
        for (var component : components(report.spec)) {
            var item = new ReportDataBundle.Component();
            item.id = component.id;
            item.title = component.title;
            item.displayType = component.display != null ? component.display.type : null;
            item.data = queryService.query(new ReportQueryService.QueryRequest(report.id, report.revision, report.specVersion,
                    querySpec(report.spec, ReportParameters.resolve(component.query, bindings)), dataset, now, override, pageSize, offset));
            bundle.components.add(item);
        }
        return bundle;
    }

    public ReportData firstComponentData(ReportDefinition report, Dataset dataset, ReportQueryService.RangeOverride override,
                                         Map<String, Object> parameters, int pageSize, int offset) {
        var bindings = ReportParameters.bindings(report.spec, parameters);
        var components = components(report.spec);
        if (components.isEmpty()) throw ReportException.badRequest("REPORT_INVALID_SPEC", "report has no components");
        return queryService.query(new ReportQueryService.QueryRequest(report.id, report.revision, report.specVersion,
                querySpec(report.spec, ReportParameters.resolve(components.get(0).query, bindings)), dataset, ZonedDateTime.now(), override, pageSize, offset));
    }

    private List<ReportData> previewComponents(ReportSpec spec, Dataset dataset, ZonedDateTime now) {
        var parameters = ReportParameters.bindings(spec, Map.of());
        var components = components(spec);
        if (components.isEmpty()) throw ReportException.badRequest("REPORT_INVALID_SPEC", "report has no components");
        var result = new ArrayList<ReportData>(components.size());
        for (var component : components) {
            result.add(queryService.query(new ReportQueryService.QueryRequest(null, 1, ReportConstants.SPEC_VERSION,
                    querySpec(spec, ReportParameters.resolve(component.query, parameters)), dataset, now, null, 100, 0)));
        }
        return result;
    }

    private List<ReportSpec.Component> components(ReportSpec spec) {
        return ReportSpecStructureValidator.get().components(spec, new ArrayList<>());
    }

    private ReportSpec querySpec(ReportSpec source, ReportSpec.Query query) {
        var spec = new ReportSpec();
        spec.specVersion = source.specVersion;
        spec.source = source.source;
        spec.query = query;
        return spec;
    }

    public String datasetName(String datasetId) {
        var dataset = datasetService.get(datasetId);
        return dataset != null ? dataset.name : null;
    }

    public List<ReportSummary> list(String userId, int offset, int limit) {
        accessPolicy.requireView(userId);
        var reports = reportStore.listOwned(userId, Math.max(offset, 0), clamp(limit));
        var pins = layoutStore.pins(userId).stream().collect(java.util.stream.Collectors.toMap(p -> p.reportId, p -> p, (a, b) -> a));
        var summaries = new ArrayList<ReportSummary>(reports.size());
        for (var report : reports) summaries.add(summary(report, pins.get(report.id)));
        return summaries;
    }

    public long count(String userId) {
        accessPolicy.requireView(userId);
        return reportStore.countOwned(userId);
    }

    public ReportSummary summary(ReportDefinition report, HomeReportPin pin) {
        var summary = new ReportSummary();
        summary.id = report.id;
        summary.title = report.title;
        summary.revision = report.revision;
        summary.datasetId = report.datasetId;
        var dataset = datasetService.get(report.datasetId);
        summary.datasetName = dataset != null ? dataset.name : null;
        summary.displayType = displayType(report.spec);
        summary.pinId = pin != null ? pin.id : null;
        summary.pinWidth = pin != null ? pin.width : null;
        summary.updatedAt = report.updatedAt;
        return summary;
    }

    public void audit(ReportDefinition report, String action, String sessionId, String agentId, long revision, String specJson) {
        var entry = new ReportAuditEntry();
        entry.id = UUID.randomUUID().toString();
        entry.userId = report.userId;
        entry.reportId = report.id;
        entry.action = action;
        entry.actorUserId = report.userId;
        entry.originSessionId = sessionId;
        entry.originAgentId = agentId;
        entry.revision = revision;
        entry.specHash = specJson != null ? ai.core.server.report.domain.ReportDependency.sha256(specJson) : null;
        entry.detailsJson = "{}";
        entry.createdAt = ZonedDateTime.now();
        auditStore.append(entry);
    }

    private ReportSpec draftDataset(String specJson) {
        try {
            var spec = JsonUtil.fromJson(ReportSpec.class, specJson);
            if (spec.source == null || spec.source.datasetId == null || spec.source.datasetId.isBlank()) {
                throw ReportException.badRequest("SOURCE_REQUIRED", "source.dataset_id is required");
            }
            return spec;
        } catch (ReportException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            throw ReportException.badRequest("REPORT_INVALID_SPEC", "invalid spec_json: " + e.getMessage(), e);
        }
    }

    private Dataset requireDataset(String datasetId) {
        var dataset = datasetService.get(datasetId);
        if (dataset == null) throw ReportException.forbidden("SOURCE_ACCESS_DENIED", "dataset is not accessible");
        if (DatasetService.resolveType(dataset) != DatasetType.GENERAL) {
            throw ReportException.badRequest("SOURCE_NOT_GENERAL", "only GENERAL datasets can back a report");
        }
        return dataset;
    }

    private void lockUserReportQuota(String userId) {
        database.selectOne("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtext(?))) AS l",
                PgReportLockRow.class, userId);
    }

    private int clamp(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, 100);
    }

    public record ReportDraft(String title, String description, String specJson) {
    }
}
