package ai.core.server.report.web;

import ai.core.api.server.report.DeletePinRequest;
import ai.core.api.server.report.DeleteReportResponse;
import ai.core.api.server.report.HomeReportWebService;
import ai.core.api.server.report.ReportLayoutRequest;
import ai.core.api.server.report.ReportPinCreateRequest;
import ai.core.api.server.report.ReportPinDataView;
import ai.core.api.server.report.ReportPinView;
import ai.core.api.server.report.ReportPinsDataRequest;
import ai.core.api.server.report.ReportPinsDataResponse;
import ai.core.api.server.report.ReportPinsView;
import ai.core.server.report.application.HomeReportService;
import ai.core.server.report.application.ReportQueryService;
import ai.core.server.report.application.ReportService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.UnauthorizedException;


/**
 * @author core-ai
 */
public class HomeReportWebServiceImpl implements HomeReportWebService {
    @Inject
    WebContext webContext;

    @Inject
    HomeReportService homeReportService;

    @Inject
    ReportService reportService;

    @Override
    @PermissionsRequired(PermissionCodes.DASHBOARD_VIEW)
    public ReportPinsView layout() {
        return view(homeReportService.layout(userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DASHBOARD_VIEW)
    public ReportPinView pin(ReportPinCreateRequest request) {
        if (request == null || request.reportId == null) throw new BadRequestException("report_id is required");
        if (request.expectedLayoutRevision == null) throw new BadRequestException("expected_layout_revision is required");
        var userId = userId();
        var pin = homeReportService.pin(userId, request.reportId, request.width, request.expectedLayoutRevision);
        var report = reportService.getShared(userId, request.reportId);
        return ReportViewMapper.pin(pin, reportService.summary(report, pin));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DASHBOARD_VIEW)
    public DeleteReportResponse unpin(String id, DeletePinRequest request) {
        if (request == null || request.expectedLayoutRevision == null) {
            throw new BadRequestException("expected_layout_revision is required");
        }
        homeReportService.unpin(userId(), id, request.expectedLayoutRevision);
        var response = new DeleteReportResponse();
        response.deleted = Boolean.TRUE;
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.DASHBOARD_VIEW)
    public ReportPinsView reorder(ReportLayoutRequest request) {
        if (request == null || request.expectedLayoutRevision == null || request.items == null) {
            throw new BadRequestException("expected_layout_revision and items are required");
        }
        var items = request.items.stream().map(item -> new HomeReportService.ReorderItem(item.pinId, item.width)).toList();
        homeReportService.reorder(userId(), items, request.expectedLayoutRevision);
        return view(homeReportService.layout(userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DASHBOARD_VIEW)
    public ReportPinsDataResponse data(ReportPinsDataRequest request) {
        if (request == null || request.pinIds == null) throw new BadRequestException("pin_ids is required");
        var override = new ReportQueryService.RangeOverride(request.rangeKind, request.days, request.from, request.to);
        int pageSize = request.pageSize != null ? request.pageSize : 100;
        int offset = request.offset != null ? request.offset : 0;
        var items = homeReportService.data(userId(), request.pinIds, override, pageSize, offset);
        var response = new ReportPinsDataResponse();
        response.items = items.stream().map(this::item).toList();
        return response;
    }

    private ReportPinsView view(HomeReportService.LayoutData data) {
        var view = new ReportPinsView();
        view.layoutRevision = data.revision();
        view.pins = data.pins().stream().map(ReportViewMapper::pin).toList();
        return view;
    }

    private ReportPinDataView item(HomeReportService.PinData data) {
        var view = new ReportPinDataView();
        view.pinId = data.pin != null ? data.pin.id : null;
        view.reportId = data.pin != null ? data.pin.reportId : null;
        view.data = data.data != null ? ReportViewMapper.data(data.data) : null;
        view.errorCode = data.errorCode;
        view.errorMessage = data.errorMessage;
        return view;
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        return userId;
    }
}
