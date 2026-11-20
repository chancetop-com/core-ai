package ai.core.server.selfharness;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.DatasetType;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.report.application.ReportException;
import ai.core.server.report.domain.ReportData;
import ai.core.tool.OutboundCallerContext;
import ai.core.server.domain.Dataset;
import ai.core.server.report.application.ReportService;
import ai.core.server.report.application.ReportSpecValidator;
import ai.core.server.report.domain.ReportDefinition;
import ai.core.server.report.domain.ReportSummary;
import ai.core.server.session.SessionDatasetHelper;
import ai.core.session.CustomEventEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The report tools of the dataset group must stay owner-scoped, forward the revision CAS and fail
 * with a readable message when the report feature is disabled.
 */
class SelfHarnessReportDispatcherTest {
    private static final String SPEC = "{\"spec_version\":1}";

    private final ReportService reportService = mock(ReportService.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final CustomEventEmitter emitter = mock(CustomEventEmitter.class);
    private final PermissionService permissionService = mock(PermissionService.class);

    @AfterEach
    void clearReportService() {
        SessionDatasetHelper.setReportService(null);
    }

    @Test
    void listFiltersByDatasetAndPaginates() {
        when(reportService.list("u1", 0, ReportService.MAX_REPORTS_PER_USER)).thenReturn(List.of(
                summary("r1", "d1"), summary("r2", "d2"), summary("r3", "d1")));

        var result = (Map<?, ?>) dispatcher().dispatch("list_dataset_reports",
                "{\"dataset_id\":\"d1\",\"limit\":1,\"offset\":1}", context());

        var reports = (List<?>) result.get("reports");
        assertEquals(1, reports.size());
        assertEquals(2, result.get("total"));
        assertEquals("r3", ((Map<?, ?>) reports.getFirst()).get("report_id"));
    }

    @Test
    void createUsesTheCallerAsOwnerAndReportsTheSavedId() {
        var report = report("r1", "d1");
        when(reportService.create("u1", "s1", null, "Daily volume", null, SPEC)).thenReturn(report);
        when(datasetService.get("d1")).thenReturn(dataset("d1"));

        var result = (Map<?, ?>) dispatcher().dispatch("create_dataset_report",
                "{\"title\":\"Daily volume\",\"spec_json\":\"{\\\"spec_version\\\":1}\"}", context());

        assertEquals("r1", result.get("report_id"));
        assertEquals("d1", result.get("dataset_id"));
        assertEquals("HR resumes", result.get("dataset_name"));
        assertEquals("/datasets/reports/r1", result.get("url"));
        verify(emitter).emit(eq("report_created"), any(), isNull(), isNull());
    }

    @Test
    void updateForwardsTheExpectedRevision() {
        when(reportService.update(eq("u1"), eq("s1"), isNull(), eq("r1"), eq(3L), any(ReportService.ReportDraft.class)))
                .thenReturn(report("r1", "d1"));

        var result = (Map<?, ?>) dispatcher().dispatch("update_dataset_report",
                "{\"report_id\":\"r1\",\"expected_revision\":3,\"title\":\"Daily volume\",\"spec_json\":\"{\\\"spec_version\\\":1}\"}",
                context());

        assertEquals(1L, result.get("revision"));
        var captor = ArgumentCaptor.forClass(ReportService.ReportDraft.class);
        verify(reportService).update(eq("u1"), eq("s1"), isNull(), eq("r1"), eq(3L), captor.capture());
        assertEquals(SPEC, captor.getValue().specJson());
        verify(emitter).emit(eq("report_updated"), any(), isNull(), isNull());
    }

    @Test
    void reportToolsFailClearlyWhenReportsAreDisabled() {
        var dispatcher = new SelfHarnessReportDispatcher();
        dispatcher.datasetService = datasetService;

        var error = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch("list_dataset_reports", "{}", context()));

        assertTrue(error.getMessage().contains("not enabled"));
    }

    @Test
    void validationFailureCarriesEveryIssue() {
        var issues = List.of(new ReportSpecValidator.Issue("UNKNOWN_SERIES_FIELD", "/display/series/0/field",
                "measure id", "reference a declared measure"));
        when(reportService.preview("u1", "Daily volume", null, SPEC))
                .thenThrow(new ReportSpecValidator.ReportValidationException(issues));

        var error = assertThrows(IllegalArgumentException.class,
                () -> dispatcher().dispatch("preview_dataset_report",
                        "{\"title\":\"Daily volume\",\"spec_json\":\"{\\\"spec_version\\\":1}\"}", context()));

        assertTrue(error.getMessage().contains("REPORT_INVALID_SPEC"));
        assertTrue(error.getMessage().contains("UNKNOWN_SERIES_FIELD"));
    }

    @Test
    void sourcesUseDatasetPermissionAndExcludeSessionState() {
        var general = dataset("d1");
        var session = dataset("s2");
        session.type = DatasetType.SESSION;
        when(datasetService.list("HR", 0, 20)).thenReturn(List.of(general, session));
        when(datasetService.count("HR")).thenReturn(2L);
        var result = (Map<?, ?>) dispatcher().dispatch("list_report_sources", "{\"q\":\"HR\"}", context());
        verify(permissionService).check("u1", PermissionCodes.DATASET_VIEW);
        var sources = (List<?>) result.get("sources");
        assertEquals(1, sources.size());
        assertEquals("d1", ((Map<?, ?>) sources.getFirst()).get("dataset_id"));
        assertEquals(2, result.get("next_offset"));
    }

    @Test
    void callerIdentityWinsOverTheContextAndToolArguments() {
        var context = context();
        context.setCaller(new OutboundCallerContext.Caller(null, "authenticated-user", null, Map.of()));
        when(reportService.get("authenticated-user", "r1")).thenReturn(report("r1", "d1"));
        dispatcher().dispatch("get_dataset_report", "{\"report_id\":\"r1\",\"user_id\":\"other-user\"}", context);
        verify(reportService).get("authenticated-user", "r1");
    }

    @Test
    void rejectsMissingUserInsteadOfFallingBackToInternal() {
        var error = assertThrows(ReportException.class,
                () -> dispatcher().dispatch("list_dataset_reports", "{}", ExecutionContext.empty()));
        assertEquals("AUTH_REQUIRED", error.errorCode());
    }

    @Test
    void previewMarksTruncatedRowsAsHasMore() {
        var data = new ReportData();
        for (int i = 0; i < 25; i++) data.rows.add(List.of(String.valueOf(i)));
        when(reportService.preview("u1", "Daily volume", null, SPEC)).thenReturn(data);
        var result = (Map<?, ?>) dispatcher().dispatch("preview_dataset_report",
                "{\"title\":\"Daily volume\",\"spec_json\":\"{\\\"spec_version\\\":1}\"}", context());
        assertEquals(20, ((List<?>) result.get("rows")).size());
        assertTrue((Boolean) result.get("has_more"));
    }

    private SelfHarnessReportDispatcher dispatcher() {
        var dispatcher = new SelfHarnessReportDispatcher();
        dispatcher.datasetService = datasetService;
        dispatcher.permissionService = permissionService;
        SessionDatasetHelper.setReportService(reportService);
        return dispatcher;
    }

    private ExecutionContext context() {
        return ExecutionContext.builder()
                .userId("u1")
                .sessionId("s1")
                .customVariable(CustomEventEmitter.CONTEXT_KEY, emitter)
                .build();
    }

    private ReportSummary summary(String reportId, String datasetId) {
        var summary = new ReportSummary();
        summary.id = reportId;
        summary.title = reportId;
        summary.datasetId = datasetId;
        summary.revision = 1;
        summary.updatedAt = ZonedDateTime.now();
        return summary;
    }

    private ReportDefinition report(String reportId, String datasetId) {
        var report = new ReportDefinition();
        report.id = reportId;
        report.title = "Daily volume";
        report.datasetId = datasetId;
        report.revision = 1;
        report.specVersion = 1;
        report.engineVersion = 1;
        report.specJson = SPEC;
        report.updatedAt = ZonedDateTime.now();
        return report;
    }

    private Dataset dataset(String datasetId) {
        var dataset = new Dataset();
        dataset.id = datasetId;
        dataset.name = "HR resumes";
        return dataset;
    }
}
