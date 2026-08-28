package ai.core.server.replay.web;

import ai.core.api.server.replay.CreateReplayExperimentRequest;
import ai.core.api.server.replay.CreateReplayRunRequest;
import ai.core.api.server.replay.CreateReplayRunResponse;
import ai.core.api.server.replay.ListReplayExperimentsRequest;
import ai.core.api.server.replay.ListReplayExperimentsResponse;
import ai.core.api.server.replay.ReplayExperimentListItemView;
import ai.core.api.server.replay.ReplayExperimentView;
import ai.core.api.server.replay.ReplayRunSummaryView;
import ai.core.api.server.replay.ReplayRunView;
import ai.core.api.server.replay.ReplaySampleView;
import ai.core.api.server.replay.ReplayUsageView;
import ai.core.api.server.replay.ReplayWebService;
import ai.core.api.server.replay.UpdateReplayExperimentRequest;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayExperimentOrigin;
import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplaySample;
import ai.core.server.replay.service.ReplayService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.UnauthorizedException;

import java.util.List;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.EXPERIMENT_VIEW)
public class ReplayWebServiceImpl implements ReplayWebService {
    private static ReplayExperimentView toView(ReplayExperiment experiment, List<ReplayRun> runs) {
        var view = new ReplayExperimentView();
        view.id = experiment.id;
        view.userId = experiment.userId;
        view.origin = originName(experiment);
        view.traceId = experiment.traceId;
        view.spanId = experiment.spanId;
        view.agentId = experiment.agentId;
        view.agentName = experiment.agentName;
        view.sessionId = experiment.sessionId;
        view.traceSource = experiment.traceSource;
        view.spanName = experiment.spanName;
        view.originalModel = experiment.originalModel;
        view.originalInput = experiment.originalInput;
        view.originalOutput = experiment.originalOutput;
        view.originalParams = experiment.originalParams;
        view.originalUsage = toUsageView(experiment.originalUsage);
        view.traceSnapshot = experiment.traceSnapshot;
        view.draftRequest = experiment.draftRequest;
        view.note = experiment.note;
        view.runCount = experiment.runCount;
        view.runs = runs.stream().map(ReplayWebServiceImpl::toRunSummaryView).toList();
        view.createdAt = experiment.createdAt;
        view.updatedAt = experiment.updatedAt;
        return view;
    }

    private static ReplayExperimentOrigin parseOrigin(String origin) {
        if (origin == null || origin.isBlank()) return null;
        try {
            return ReplayExperimentOrigin.valueOf(origin.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("origin must be SPAN or BLANK", "BAD_REQUEST", e);
        }
    }

    // origin was introduced after the first records were written: fall back on trace_id presence
    private static String originName(ReplayExperiment experiment) {
        if (experiment.origin != null) return experiment.origin.name();
        return experiment.traceId != null ? "SPAN" : "BLANK";
    }

    private static ReplayExperimentListItemView toListItemView(ReplayExperiment experiment) {
        var view = new ReplayExperimentListItemView();
        view.id = experiment.id;
        view.origin = originName(experiment);
        view.spanName = experiment.spanName;
        view.agentId = experiment.agentId;
        view.agentName = experiment.agentName;
        view.originalModel = experiment.originalModel;
        view.runCount = experiment.runCount;
        view.createdAt = experiment.createdAt;
        return view;
    }

    private static ReplayRunSummaryView toRunSummaryView(ReplayRun run) {
        var view = new ReplayRunSummaryView();
        view.id = run.id;
        view.label = run.label;
        view.status = run.status != null ? run.status.name() : null;
        view.sampleCount = run.sampleCount;
        view.createdAt = run.createdAt;
        return view;
    }

    private static ReplayRunView toRunView(ReplayRun run) {
        var view = new ReplayRunView();
        view.id = run.id;
        view.experimentId = run.experimentId;
        view.label = run.label;
        view.request = run.request;
        view.model = run.model;
        view.temperature = run.temperature;
        view.reasoningEffort = run.reasoningEffort;
        view.sampleCount = run.sampleCount;
        view.samples = run.samples != null ? run.samples.stream().map(ReplayWebServiceImpl::toSampleView).toList() : null;
        view.status = run.status != null ? run.status.name() : null;
        view.createdAt = run.createdAt;
        view.completedAt = run.completedAt;
        return view;
    }

    private static ReplaySampleView toSampleView(ReplaySample sample) {
        var view = new ReplaySampleView();
        view.index = sample.index;
        view.status = sample.status != null ? sample.status.name() : null;
        view.output = sample.output;
        view.inputTokens = sample.inputTokens;
        view.outputTokens = sample.outputTokens;
        view.costUsd = sample.costUsd;
        view.durationMs = sample.durationMs;
        view.errorMessage = sample.errorMessage;
        view.replayTraceId = sample.replayTraceId;
        return view;
    }

    private static ReplayUsageView toUsageView(ai.core.server.replay.domain.ReplayUsage usage) {
        if (usage == null) return null;
        var view = new ReplayUsageView();
        view.inputTokens = usage.inputTokens;
        view.outputTokens = usage.outputTokens;
        view.cachedTokens = usage.cachedTokens;
        view.costUsd = usage.costUsd;
        view.durationMs = usage.durationMs;
        return view;
    }

    @Inject
    ReplayService replayService;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_REPLAY)
    public ReplayExperimentView create(CreateReplayExperimentRequest request) {
        var scope = scope();
        requireUser(scope);
        boolean blank = (request.traceId == null || request.traceId.isBlank()) && (request.spanId == null || request.spanId.isBlank());
        if (!blank && (request.traceId == null || request.spanId == null)) {
            throw new BadRequestException("both trace_id and span_id are required, or neither for a blank experiment");
        }
        var experiment = replayService.create(scope.userId, scope.admin, request.traceId, request.spanId);
        return toView(experiment, replayService.runSummaries(experiment.id));
    }

    @Override
    public ListReplayExperimentsResponse list(ListReplayExperimentsRequest request) {
        var scope = scope();
        requireUser(scope);
        int offset = request.offset == null ? 0 : Math.max(request.offset, 0);
        int limit = request.limit == null ? 20 : Math.clamp(request.limit, 1, ReplayService.MAX_LIST_LIMIT);
        var origin = parseOrigin(request.origin);
        var experiments = replayService.list(scope.userId, scope.admin, request.agentId, origin, offset, limit);
        var response = new ListReplayExperimentsResponse();
        response.experiments = experiments.stream().map(ReplayWebServiceImpl::toListItemView).toList();
        response.total = replayService.count(scope.userId, scope.admin, request.agentId, origin);
        return response;
    }

    @Override
    public ReplayExperimentView get(String id) {
        var scope = scope();
        requireUser(scope);
        var experiment = requireOwned(id, scope);
        return toView(experiment, replayService.runSummaries(experiment.id));
    }

    @Override
    public ReplayExperimentView update(String id, UpdateReplayExperimentRequest request) {
        var scope = scope();
        requireUser(scope);
        var experiment = replayService.update(id, scope.userId, scope.admin, request.draftRequest, request.note);
        return toView(experiment, replayService.runSummaries(experiment.id));
    }

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_REPLAY)
    public void delete(String id) {
        var scope = scope();
        requireUser(scope);
        replayService.delete(id, scope.userId, scope.admin);
    }

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_REPLAY)
    public CreateReplayRunResponse createRun(String id, CreateReplayRunRequest request) {
        var scope = scope();
        requireUser(scope);
        var run = replayService.createRun(id, scope.userId, scope.admin, request);
        var response = new CreateReplayRunResponse();
        response.runId = run.id;
        response.status = run.status.name();
        return response;
    }

    @Override
    public ReplayRunView getRun(String id, String runId) {
        var scope = scope();
        requireUser(scope);
        var experiment = requireOwned(id, scope);
        var run = replayService.getRun(runId);
        if (run == null || !experiment.id.equals(run.experimentId)) throw new NotFoundException("replay run not found");
        return toRunView(run);
    }

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_REPLAY)
    public void cancelRun(String id, String runId) {
        var scope = scope();
        requireUser(scope);
        var experiment = requireOwned(id, scope);
        var run = replayService.getRun(runId);
        if (run == null || !experiment.id.equals(run.experimentId)) throw new NotFoundException("replay run not found");
        replayService.cancelRun(runId, scope.userId, scope.admin);
    }

    private ReplayExperiment requireOwned(String id, Scope scope) {
        var experiment = replayService.get(id);
        if (experiment == null || !scope.admin && !scope.userId.equals(experiment.userId)) {
            throw new NotFoundException("replay experiment not found");
        }
        return experiment;
    }

    private Scope scope() {
        var userId = AuthContext.userId(webContext);
        var admin = userId != null && userCollection.get(userId)
                .map(user -> "admin".equals(user.role))
                .orElse(Boolean.FALSE);
        return new Scope(userId, admin);
    }

    private void requireUser(Scope scope) {
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
    }

    private record Scope(String userId, boolean admin) {
    }
}
