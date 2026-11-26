package ai.core.server.report.web;

import ai.core.api.server.report.DeleteReportRequest;
import ai.core.api.server.report.DeleteReportResponse;
import ai.core.api.server.report.ListReportsRequest;
import ai.core.api.server.report.ListReportsResponse;
import ai.core.api.server.report.ReportDataRequest;
import ai.core.api.server.report.ReportDataView;
import ai.core.api.server.report.ReportView;
import ai.core.api.server.report.ReportWebService;
import ai.core.server.report.application.ReportQueryService;
import ai.core.server.report.application.ReportService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.user.UserStore;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.UnauthorizedException;

/**
 * @author core-ai
 */
public class ReportWebServiceImpl implements ReportWebService {
    @Inject
    WebContext webContext;

    @Inject
    ReportService reportService;

    @Inject
    UserStore userStore;

    @Override
    @PermissionsRequired(PermissionCodes.REPORT_VIEW)
    public ListReportsResponse list(ListReportsRequest request) {
        var userId = userId();
        int offset = request != null && request.offset != null ? Math.max(request.offset, 0) : 0;
        int limit = request != null && request.limit != null ? request.limit : 20;
        var reports = reportService.listAll(userId, offset, limit);
        var response = new ListReportsResponse();
        response.reports = reports.stream().map(summary -> {
            var view = ReportViewMapper.report(summary);
            decorate(view, summary.userId, userId);
            return view;
        }).toList();
        response.total = reportService.countAll(userId);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.REPORT_VIEW)
    public ReportView get(String id) {
        var userId = userId();
        var report = reportService.getShared(userId, id);
        var dataset = reportService.datasetName(report.datasetId);
        var view = ReportViewMapper.report(report, dataset, reportService.pinFor(userId, id));
        view.parameterOptionsJson = reportService.parameterOptionsJson(userId, id);
        decorate(view, report.userId, userId);
        return view;
    }

    @Override
    @PermissionsRequired(PermissionCodes.REPORT_VIEW)
    public ReportDataView data(String id, ReportDataRequest request) {
        var userId = userId();
        var override = request != null
                ? new ReportQueryService.RangeOverride(request.rangeKind, request.days, request.from, request.to)
                : null;
        int pageSize = request != null && request.pageSize != null ? request.pageSize : 100;
        int offset = request != null && request.offset != null ? request.offset : 0;
        java.util.Map<String, Object> parameters;
        try {
            parameters = request != null && request.parametersJson != null && !request.parametersJson.isBlank()
                    ? JsonUtil.toMap(request.parametersJson) : java.util.Map.of();
        } catch (RuntimeException e) {
            throw new BadRequestException("invalid parameters_json", "BAD_REQUEST", e);
        }
        return ReportViewMapper.bundle(reportService.dataBundleShared(userId, id, override, parameters, pageSize, offset));
    }

    @Override
    @PermissionsRequired(PermissionCodes.REPORT_MANAGE)
    public DeleteReportResponse delete(String id, DeleteReportRequest request) {
        if (request == null || request.expectedRevision == null) {
            throw new BadRequestException("expected_revision is required");
        }
        reportService.delete(userId(), id, request.expectedRevision, request.expectedLayoutRevision);
        var response = new DeleteReportResponse();
        response.deleted = Boolean.TRUE;
        return response;
    }

    private void decorate(ReportView view, String ownerId, String currentUserId) {
        view.owned = ownerId != null && ownerId.equals(currentUserId);
        view.createdBy = ownerId != null
                ? userStore.get(ownerId).map(user -> user.name != null ? user.name : ownerId).orElse(ownerId)
                : null;
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        return userId;
    }
}
