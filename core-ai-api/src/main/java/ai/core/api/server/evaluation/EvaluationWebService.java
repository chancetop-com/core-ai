package ai.core.api.server.evaluation;

import ai.core.api.server.evaluation.request.CompareRunsRequest;
import ai.core.api.server.evaluation.request.CreateEvaluationRequest;
import ai.core.api.server.evaluation.request.CreateGraderSuiteRequest;
import ai.core.api.server.evaluation.request.CreateSpecRequest;
import ai.core.api.server.evaluation.request.GraderTestRequest;
import ai.core.api.server.evaluation.request.ImportDatasetRequest;
import ai.core.api.server.evaluation.request.ListCasesRequest;
import ai.core.api.server.evaluation.request.ListEvaluationsRequest;
import ai.core.api.server.evaluation.request.ListRunsRequest;
import ai.core.api.server.evaluation.request.ListTrialsRequest;
import ai.core.api.server.evaluation.request.SetBaselineRequest;
import ai.core.api.server.evaluation.request.StartRunRequest;
import ai.core.api.server.evaluation.request.UpdateEvaluationRequest;
import ai.core.api.server.evaluation.response.ComparisonView;
import ai.core.api.server.evaluation.response.DatasetRevisionView;
import ai.core.api.server.evaluation.response.EvalSpecView;
import ai.core.api.server.evaluation.response.EvaluationView;
import ai.core.api.server.evaluation.response.GraderSuiteView;
import ai.core.api.server.evaluation.response.GraderTestView;
import ai.core.api.server.evaluation.response.ListCasesResponse;
import ai.core.api.server.evaluation.response.ListDatasetsResponse;
import ai.core.api.server.evaluation.response.ListEvaluationsResponse;
import ai.core.api.server.evaluation.response.ListGraderSuitesResponse;
import ai.core.api.server.evaluation.response.ListRunsResponse;
import ai.core.api.server.evaluation.response.ListSpecsResponse;
import ai.core.api.server.evaluation.response.ListTrialsResponse;
import ai.core.api.server.evaluation.response.RunReportView;
import ai.core.api.server.evaluation.response.RunView;
import ai.core.api.server.evaluation.response.TrialDetailView;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * Offline agent evaluation management. The first release is a human-admin pilot.
 *
 * @author stephen
 */
public interface EvaluationWebService {
    @GET
    @Path("/api/evaluations")
    ListEvaluationsResponse list(ListEvaluationsRequest request);

    @POST
    @Path("/api/evaluations")
    @ResponseStatus(HTTPStatus.OK)
    EvaluationView create(CreateEvaluationRequest request);

    @GET
    @Path("/api/evaluations/:id")
    EvaluationView get(@PathParam("id") String id);

    @PUT
    @Path("/api/evaluations/:id")
    EvaluationView update(@PathParam("id") String id, UpdateEvaluationRequest request);

    @POST
    @Path("/api/evaluations/:id/archive")
    void archive(@PathParam("id") String id);

    @POST
    @Path("/api/evaluations/:id/dataset-imports")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    DatasetRevisionView importDataset(@PathParam("id") String id, ImportDatasetRequest request);

    @GET
    @Path("/api/evaluations/:id/datasets")
    ListDatasetsResponse listDatasets(@PathParam("id") String id);

    @GET
    @Path("/api/evaluations/:id/datasets/:datasetId/cases")
    ListCasesResponse listCases(@PathParam("id") String id, @PathParam("datasetId") String datasetId, ListCasesRequest request);

    @POST
    @Path("/api/evaluations/:id/grader-tests")
    GraderTestView testGraderSuite(@PathParam("id") String id, GraderTestRequest request);

    @POST
    @Path("/api/evaluations/:id/grader-suites")
    @ResponseStatus(HTTPStatus.OK)
    GraderSuiteView createGraderSuite(@PathParam("id") String id, CreateGraderSuiteRequest request);

    @GET
    @Path("/api/evaluations/:id/grader-suites")
    ListGraderSuitesResponse listGraderSuites(@PathParam("id") String id);

    @POST
    @Path("/api/evaluations/:id/spec-revisions")
    @ResponseStatus(HTTPStatus.OK)
    EvalSpecView createSpec(@PathParam("id") String id, CreateSpecRequest request);

    @GET
    @Path("/api/evaluations/:id/spec-revisions")
    ListSpecsResponse listSpecs(@PathParam("id") String id);

    @POST
    @Path("/api/evaluations/:id/runs")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    RunView startRun(@PathParam("id") String id, StartRunRequest request);

    @GET
    @Path("/api/evaluations/:id/runs")
    ListRunsResponse listRuns(@PathParam("id") String id, ListRunsRequest request);

    @GET
    @Path("/api/evaluations/:id/runs/:runId")
    RunView getRun(@PathParam("id") String id, @PathParam("runId") String runId);

    @GET
    @Path("/api/evaluations/:id/runs/:runId/report")
    RunReportView report(@PathParam("id") String id, @PathParam("runId") String runId);

    @GET
    @Path("/api/evaluations/:id/runs/:runId/trials")
    ListTrialsResponse listTrials(@PathParam("id") String id, @PathParam("runId") String runId, ListTrialsRequest request);

    @GET
    @Path("/api/evaluations/:id/runs/:runId/trials/:trialId")
    TrialDetailView getTrial(@PathParam("id") String id, @PathParam("runId") String runId,
                             @PathParam("trialId") String trialId);

    @POST
    @Path("/api/evaluations/:id/runs/:runId/cancel")
    void cancel(@PathParam("id") String id, @PathParam("runId") String runId);

    @PUT
    @Path("/api/evaluations/:id/baseline")
    EvaluationView selectBaseline(@PathParam("id") String id, SetBaselineRequest request);

    @POST
    @Path("/api/evaluations/:id/comparisons")
    ComparisonView compare(@PathParam("id") String id, CompareRunsRequest request);
}
