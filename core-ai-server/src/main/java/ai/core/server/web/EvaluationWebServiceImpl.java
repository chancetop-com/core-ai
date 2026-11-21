package ai.core.server.web;

import ai.core.api.server.evaluation.EvaluationWebService;
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
import ai.core.server.apiuser.PermissionService;
import ai.core.server.evaluation.service.EvalRunService;
import ai.core.server.evaluation.service.EvalSpecDraft;
import ai.core.server.evaluation.service.EvaluationService;
import ai.core.server.evaluation.service.StartRunDraft;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

import java.util.ArrayList;

/**
 * @author stephen
 */
public class EvaluationWebServiceImpl implements EvaluationWebService {
    @Inject
    WebContext webContext;
    @Inject
    EvaluationService evaluationService;
    @Inject
    EvalRunService runService;
    @Inject
    PermissionService permissionService;

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ListEvaluationsResponse list(ListEvaluationsRequest request) {
        var actorId = actor();
        int offset = request.offset == null ? 0 : Math.max(0, request.offset);
        int limit = request.limit == null ? 20 : Math.clamp(request.limit, 1, 200);
        var response = new ListEvaluationsResponse();
        response.evaluations = new ArrayList<>(16);
        for (var entity : evaluationService.list(actorId, request.agentId, offset, limit)) {
            response.evaluations.add(EvaluationViews.evaluation(entity));
        }
        response.total = evaluationService.count(actorId, request.agentId);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public EvaluationView create(CreateEvaluationRequest request) {
        return EvaluationViews.evaluation(evaluationService.create(request.agentId, request.name, request.description, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public EvaluationView get(String id) {
        return EvaluationViews.evaluation(evaluationService.get(id, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public EvaluationView update(String id, UpdateEvaluationRequest request) {
        return EvaluationViews.evaluation(evaluationService.update(id, request.name, request.description, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public void archive(String id) {
        evaluationService.archive(id, actor());
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public DatasetRevisionView importDataset(String id, ImportDatasetRequest request) {
        return EvaluationViews.dataset(evaluationService.importDataset(id, request.fileId, request.name, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ListDatasetsResponse listDatasets(String id) {
        var response = new ListDatasetsResponse();
        response.datasets = new ArrayList<>(16);
        for (var dataset : evaluationService.listDatasets(id, actor())) response.datasets.add(EvaluationViews.dataset(dataset));
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ListCasesResponse listCases(String id, String datasetId, ListCasesRequest request) {
        var dataset = evaluationService.requireDataset(id, datasetId, actor());
        var oracle = canReadOracle();
        int offset = request.offset == null ? 0 : Math.max(0, request.offset);
        int limit = request.limit == null ? 50 : Math.clamp(request.limit, 1, 200);
        var response = new ListCasesResponse();
        response.cases = new ArrayList<>(16);
        for (var entity : evaluationService.listCases(dataset.id, request.split, offset, limit)) {
            response.cases.add(EvaluationViews.evalCase(entity, oracle));
        }
        response.total = evaluationService.countCases(dataset.id, request.split);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public GraderTestView testGraderSuite(String id, GraderTestRequest request) {
        var response = new GraderTestView();
        response.receiptJson = evaluationService.testGraderSuite(id, request.bodyJson, actor());
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public GraderSuiteView createGraderSuite(String id, CreateGraderSuiteRequest request) {
        return EvaluationViews.graderSuite(evaluationService.createGraderSuite(id, request.bodyJson, request.name, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ListGraderSuitesResponse listGraderSuites(String id) {
        var response = new ListGraderSuitesResponse();
        response.suites = new ArrayList<>(16);
        for (var suite : evaluationService.listGraderSuites(id, actor())) response.suites.add(EvaluationViews.graderSuite(suite));
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public EvalSpecView createSpec(String id, CreateSpecRequest request) {
        var draft = new EvalSpecDraft(request.datasetId, request.graderSuiteId, request.profile,
            request.repetitions, request.agentConcurrency, request.trialDeadlineSeconds, request.trialMaxTurns,
            request.runDeadlineSeconds, request.split, request.caseIds, request.defaultClock);
        return EvaluationViews.spec(evaluationService.createSpec(id, draft, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ListSpecsResponse listSpecs(String id) {
        var response = new ListSpecsResponse();
        response.specs = new ArrayList<>(16);
        for (var spec : evaluationService.listSpecs(id, actor())) response.specs.add(EvaluationViews.spec(spec));
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_RUN)
    public RunView startRun(String id, StartRunRequest request) {
        var draft = new StartRunDraft(request.specId, request.source, request.snapshotId,
            request.purpose, request.experimentLabel, request.requestKey);
        return EvaluationViews.run(runService.start(id, draft, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ListRunsResponse listRuns(String id, ListRunsRequest request) {
        var actorId = actor();
        int offset = request.offset == null ? 0 : Math.max(0, request.offset);
        int limit = request.limit == null ? 20 : Math.clamp(request.limit, 1, 200);
        var response = new ListRunsResponse();
        response.runs = new ArrayList<>(16);
        for (var run : runService.listRuns(id, actorId, offset, limit)) response.runs.add(EvaluationViews.run(run));
        response.total = runService.countRuns(id, actorId);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public RunView getRun(String id, String runId) {
        return EvaluationViews.run(runService.getRun(id, runId, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public RunReportView report(String id, String runId) {
        return EvaluationViews.report(runService.report(id, runId, actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ListTrialsResponse listTrials(String id, String runId, ListTrialsRequest request) {
        var actorId = actor();
        int offset = request.offset == null ? 0 : Math.max(0, request.offset);
        int limit = request.limit == null ? 50 : Math.clamp(request.limit, 1, 200);
        var query = new EvalRunService.TrialQuery(request.status, request.caseId, request.taskResult, offset, limit);
        var response = new ListTrialsResponse();
        response.trials = new ArrayList<>(16);
        for (var trial : runService.listTrials(id, runId, actorId, query)) response.trials.add(EvaluationViews.trial(trial));
        response.total = runService.countTrials(id, runId, actorId, query);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public TrialDetailView getTrial(String id, String runId, String trialId) {
        var actorId = actor();
        var trial = runService.getTrial(id, runId, trialId, actorId);
        var evalCase = runService.caseForTrial(id, runId, trialId, actorId);
        return EvaluationViews.trialDetail(trial, evalCase, canReadOracle());
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_RUN)
    public void cancel(String id, String runId) {
        runService.cancel(id, runId, actor());
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_MANAGE)
    public EvaluationView selectBaseline(String id, SetBaselineRequest request) {
        var evaluation = runService.chooseBaseline(id, request.runId, request.reason,
            Boolean.TRUE.equals(request.acknowledgeUnpinned),
            request.expectedGeneration == null ? -1 : request.expectedGeneration, actor());
        return EvaluationViews.evaluation(evaluation);
    }

    @Override
    @PermissionsRequired(PermissionCodes.EVALUATION_VIEW)
    public ComparisonView compare(String id, CompareRunsRequest request) {
        return EvaluationViews.comparison(runService.compare(id, request.baselineRunId, request.candidateRunId, actor()));
    }

    private String actor() {
        return AuthContext.userId(webContext);
    }

    private boolean canReadOracle() {
        return permissionService.has(actor(), PermissionCodes.EVALUATION_ORACLE_VIEW);
    }
}
