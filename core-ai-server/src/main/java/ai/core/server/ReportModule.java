package ai.core.server;

import ai.core.api.server.report.HomeReportWebService;
import ai.core.api.server.report.ReportWebService;
import ai.core.server.report.application.HomeReportService;
import ai.core.server.report.application.ReportAccessPolicy;
import ai.core.server.report.application.ReportQueryService;
import ai.core.server.report.application.ReportService;
import ai.core.server.report.application.ReportSpecValidator;
import ai.core.server.report.store.PgDatasetReportRow;
import ai.core.server.report.store.PgDatasetReportStore;
import ai.core.server.report.store.PgHomeReportLayoutRow;
import ai.core.server.report.store.PgHomeReportLayoutStore;
import ai.core.server.report.store.PgHomeReportPinRow;
import ai.core.server.report.store.PgReportAuditRow;
import ai.core.server.report.store.PgReportAuditStore;
import ai.core.server.report.store.PgReportInvalidCountRow;
import ai.core.server.report.store.PgReportLockRow;
import ai.core.server.report.store.PgReportPositionRow;
import ai.core.server.report.store.PgReportRangeRow;
import ai.core.server.report.store.PgReportRecordRow;
import ai.core.server.report.web.HomeReportWebServiceImpl;
import ai.core.server.report.web.ReportWebServiceImpl;
import core.framework.module.Module;

/**
 * Report domain wiring. The feature is only available on the PostgreSQL dataset backend and behind
 * {@code sys.report.enabled}; there is no Mongo fallback.
 *
 * @author core-ai
 */
public class ReportModule extends Module {
    @Override
    protected void initialize() {
        var enabled = Boolean.parseBoolean(property("sys.report.enabled").orElse("false"));
        var datasetStore = property("sys.dataset.store").orElse("mongo");
        if (!enabled || !"postgresql".equals(datasetStore)) {
            ai.core.server.session.SessionDatasetHelper.setReportService(null);
            return;
        }

        db().view(PgDatasetReportRow.class);
        db().view(PgHomeReportLayoutRow.class);
        db().view(PgHomeReportPinRow.class);
        db().view(PgReportAuditRow.class);
        db().view(PgReportRangeRow.class);
        db().view(PgReportInvalidCountRow.class);
        db().view(PgReportRecordRow.class);
        db().view(PgReportLockRow.class);
        db().view(PgReportPositionRow.class);

        bind(ReportAccessPolicy.class);
        bind(ReportSpecValidator.class);
        bind(ReportQueryService.class);
        bind(PgDatasetReportStore.class);
        bind(PgHomeReportLayoutStore.class);
        bind(PgReportAuditStore.class);
        bind(ReportService.class);
        bind(HomeReportService.class);
        ai.core.server.session.SessionDatasetHelper.setReportService(bean(ReportService.class));

        api().service(ReportWebService.class, bind(ReportWebServiceImpl.class));
        api().service(HomeReportWebService.class, bind(HomeReportWebServiceImpl.class));
    }
}
