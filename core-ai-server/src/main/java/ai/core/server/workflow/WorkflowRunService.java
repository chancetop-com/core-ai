package ai.core.server.workflow;

import ai.core.api.server.workflow.PendingInputView;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Creates and reads workflow runs. createRun pins the latest published version and inserts a PENDING run with
 * lease_until=now so the WorkflowRunnerJob claims and drives it on the next tick (same path as crash recovery).
 *
 * @author Xander
 */
public class WorkflowRunService {
    private static final String ROOT_SCOPE_KEY = "";   // P0 drives root scope only; resume mirrors that

    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;

    @Inject
    WorkflowPublishService publishService;

    @Inject
    WorkflowGraphLoader graphLoader;

    public WorkflowRun createRun(String workflowId, String input, TriggerType triggeredBy, String userId) {
        WorkflowDefinition definition = definitionCollection.get(workflowId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + workflowId));
        // published == public: any user may run the published version (the run is attributed to the caller). An
        // unpublished workflow stays private — only its owner can see it, and even they must publish it first.
        if (definition.publishedVersionId == null) {
            if (!definition.userId.equals(userId)) {
                throw new ForbiddenException("workflow does not belong to the current user: " + workflowId);
            }
            throw new BadRequestException("workflow is not published: " + workflowId);
        }
        WorkflowPublishedVersion version = versionCollection.get(definition.publishedVersionId)
            .orElseThrow(() -> new IllegalStateException("published version missing: " + definition.publishedVersionId));
        return insertRun(definition, version, input, triggeredBy, userId);
    }

    /** Run the current DRAFT without publishing: snapshot it into a throwaway preview version and run that. */
    public WorkflowRun createPreviewRun(String workflowId, String input, TriggerType triggeredBy, String userId) {
        WorkflowDefinition definition = definitionCollection.get(workflowId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + workflowId));
        if (!definition.userId.equals(userId)) {
            throw new ForbiddenException("workflow does not belong to the current user: " + workflowId);
        }
        WorkflowPublishedVersion version = publishService.createPreviewVersion(workflowId, userId);
        return insertRun(definition, version, input, triggeredBy, userId);
    }

    /**
     * Start a new run that resumes a source run from {@code resumeNodeId}: the node and its forward cone re-execute,
     * while every other node is frozen and seeded from the source run's facts (outputs / artifacts / branch choices).
     * Same pinned version as the source — the graph topology must match for the seeded facts to line up. The planner
     * then derives the resume node as the only ready node, so driving the new run continues from exactly there.
     */
    public WorkflowRun resumeFromNode(String sourceRunId, String resumeNodeId, String userId) {
        WorkflowRun source = getRun(sourceRunId, userId);   // ownership + existence
        if (source.versionId == null) {
            throw new BadRequestException("source run has no pinned version, cannot resume: " + sourceRunId);
        }
        // A preview source run's version snapshot is TTL'd after a day; resuming it then has no graph to seed against.
        if (versionCollection.get(source.versionId).isEmpty()) {
            throw new BadRequestException("source run's version snapshot has expired, cannot resume: " + sourceRunId);
        }
        WorkflowGraph graph = graphLoader.load(source.versionId);
        if (graph.node(resumeNodeId) == null) {
            throw new BadRequestException("node not found in run graph: " + resumeNodeId);
        }
        List<WorkflowNodeRun> priorRuns = nodeRunCollection.find(Filters.eq("run_id", sourceRunId));
        requireExecuted(priorRuns, resumeNodeId, sourceRunId);

        Set<String> rerun = graph.descendantsInclusive(resumeNodeId);   // resume node + its forward cone re-run fresh
        String nextRunId = UUID.randomUUID().toString();
        var journal = new MongoWorkflowJournal(nodeRunCollection);
        for (WorkflowNodeRun prior : priorRuns) {
            if (rerun.contains(prior.nodeId) || !ROOT_SCOPE_KEY.equals(prior.scopePathKey)) {
                continue;   // re-run set carries no seed; container scopes are out of scope for P0
            }
            if (prior.status == NodeRunStatus.COMPLETED || prior.status == NodeRunStatus.SKIPPED) {
                journal.seed(nextRunId, Boolean.TRUE.equals(source.preview), prior);   // freeze: only settled facts feed the resumed frontier
            }
        }
        // Insert the run row LAST, after the frozen prefix is fully seeded. WorkflowRunnerJob claims runs by scanning
        // workflow_runs; until this row exists the run is invisible/unclaimable, so no worker can drive a half-seeded
        // journal (which would re-dispatch un-seeded frozen nodes and lose their facts to the seed duplicate-key path).
        return insertResumedRun(nextRunId, source, resumeNodeId);
    }

    // The resume node must have actually executed in the source run (COMPLETED, or FAILED so a failed step can be
    // retried). A SKIPPED / never-reached node has no executed prefix to resume from, so reject it up front.
    private void requireExecuted(List<WorkflowNodeRun> priorRuns, String resumeNodeId, String sourceRunId) {
        for (WorkflowNodeRun prior : priorRuns) {
            if (resumeNodeId.equals(prior.nodeId) && ROOT_SCOPE_KEY.equals(prior.scopePathKey)
                && (prior.status == NodeRunStatus.COMPLETED || prior.status == NodeRunStatus.FAILED_RETRYABLE)) {
                return;
            }
        }
        throw new BadRequestException("node was not executed in source run " + sourceRunId + ", cannot resume from it: " + resumeNodeId);
    }

    private WorkflowRun insertResumedRun(String runId, WorkflowRun source, String resumeNodeId) {
        var now = ZonedDateTime.now();
        var run = new WorkflowRun();
        run.id = runId;
        run.workflowId = source.workflowId;
        run.versionId = source.versionId;
        run.versionSha256 = source.versionSha256;
        run.userId = source.userId;
        run.mode = source.mode;
        run.triggeredBy = source.triggeredBy;
        run.status = RunStatus.PENDING;
        run.input = source.input;
        run.preview = Boolean.TRUE.equals(source.preview);   // a resume of a preview run is itself a preview run (co-expires)
        run.resumedFromRunId = source.id;
        run.resumeFromNodeId = resumeNodeId;
        run.leaseUntil = now;   // claimable immediately by the runner job
        run.createdAt = now;
        runCollection.insert(run);
        return run;
    }

    private WorkflowRun insertRun(WorkflowDefinition definition, WorkflowPublishedVersion version, String input,
                                  TriggerType triggeredBy, String userId) {
        var now = ZonedDateTime.now();
        var run = new WorkflowRun();
        run.id = UUID.randomUUID().toString();
        run.workflowId = definition.id;
        run.versionId = version.id;
        run.versionSha256 = version.sha256;
        run.userId = userId;
        run.mode = definition.mode;
        run.triggeredBy = triggeredBy;
        run.status = RunStatus.PENDING;
        run.input = input;
        run.preview = Boolean.TRUE.equals(version.preview);   // co-expire with the throwaway preview version
        run.leaseUntil = now;   // claimable immediately by the runner job
        run.createdAt = now;
        runCollection.insert(run);
        return run;
    }

    public WorkflowRun getRun(String runId, String userId) {
        WorkflowRun run = runCollection.get(runId)
            .orElseThrow(() -> new NotFoundException("workflow run not found: " + runId));
        if (!run.userId.equals(userId)) {
            throw new ForbiddenException("workflow run does not belong to the current user: " + runId);
        }
        return run;
    }

    public List<WorkflowRun> listRuns(String workflowId, String userId) {
        return runCollection.find(Filters.and(
            Filters.eq("workflow_id", workflowId),
            Filters.eq("user_id", userId)));
    }

    public List<WorkflowNodeRun> listNodeRuns(String runId, String userId) {
        getRun(runId, userId);   // ownership check
        return nodeRunCollection.find(Filters.eq("run_id", runId));
    }

    /**
     * Settle a HUMAN_INPUT node a run is waiting on, then make sure the run gets driven. For mode=approval the
     * human's approve/reject picks the node's approve/reject out-edge (Branch); for mode=input the human's value
     * becomes the node output.
     *
     * <p>Accepts both PAUSED and RUNNING runs. A run with parallel branches still in flight stays RUNNING while a
     * HUMAN_INPUT node is already WAITING, so requiring PAUSED would reject the approve with a 409 (the node-level
     * WAITING the UI shows precedes the run-level PAUSED). The settle is a node-status-guarded CAS that is safe
     * regardless of run status; the active driver re-folds it on its next poll. The wake below flips a PAUSED run
     * (no driver) back to PENDING; it no-ops on a RUNNING run, whose live driver already owns the re-fold.
     */
    public void resume(String runId, String nodeId, Boolean approve, String input, String userId) {
        WorkflowRun run = getRun(runId, userId);   // ownership
        if (run.status != RunStatus.PAUSED && run.status != RunStatus.RUNNING) {
            throw new ConflictException("workflow run is not awaiting input (status=" + run.status + "): " + runId);
        }
        WorkflowNode node = resolveHumanNode(run, nodeId);
        HumanInputProtocol.validate(node, approve, input);   // mode + form schema enforced before settling
        long settled = nodeRunCollection.update(
            Filters.and(Filters.eq("run_id", runId), Filters.eq("node_id", nodeId), Filters.eq("status", NodeRunStatus.WAITING)),
            settleHumanNode(node, approve, input));
        if (settled == 0) {
            throw new ConflictException("node is not awaiting human input (already settled by a concurrent resume?): " + nodeId);
        }
        // wake: PAUSED -> PENDING, claimable now (guarded on PAUSED so a concurrent resume only wakes once, and so a
        // RUNNING run's live driver is left to re-fold the settle itself)
        runCollection.update(
            Filters.and(Filters.eq("_id", runId), Filters.eq("status", RunStatus.PAUSED)),
            Updates.combine(Updates.set("status", RunStatus.PENDING), Updates.set("lease_until", ZonedDateTime.now())));
    }

    /** The self-describing waits of a PAUSED run, so a single GET tells an API caller how to resume. */
    public List<PendingInputView> pendingInputs(WorkflowRun run) {
        if (run.status != RunStatus.PAUSED) {
            return List.of();
        }
        List<WorkflowNodeRun> waiting = nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", run.id), Filters.eq("status", NodeRunStatus.WAITING)));
        if (waiting.isEmpty()) {
            return List.of();
        }
        var graph = graphLoader.load(run.versionId);
        return waiting.stream().map(nodeRun -> HumanInputProtocol.describe(graph.node(nodeRun.nodeId), nodeRun)).toList();
    }

    private WorkflowNode resolveHumanNode(WorkflowRun run, String nodeId) {
        WorkflowNode node;
        try {
            node = graphLoader.load(run.versionId).node(nodeId);
        } catch (RuntimeException e) {
            throw new BadRequestException("node not found in run graph: " + nodeId);
        }
        if (!"HUMAN_INPUT".equals(node.type())) {
            throw new BadRequestException("node is not a human-input node: " + nodeId);
        }
        return node;
    }

    private static Bson settleHumanNode(WorkflowNode node, Boolean approve, String input) {
        var now = ZonedDateTime.now();
        String mode = node.config().get("mode") instanceof String value ? value : "approval";
        if ("approval".equals(mode)) {
            boolean approved = Boolean.TRUE.equals(approve);
            String edgeKey = approved ? "approve_edge_id" : "reject_edge_id";
            if (!(node.config().get(edgeKey) instanceof String edgeId) || edgeId.isBlank()) {
                throw new BadRequestException("human-input node has no " + edgeKey);
            }
            return Updates.combine(
                Updates.set("status", NodeRunStatus.COMPLETED),
                Updates.set("chosen_edge_ids", List.of(edgeId)),   // human acts as the branch decision
                Updates.set("output", JSON.toJSON(Map.of("approved", approved))),
                Updates.set("completed_at", now));
        }
        // input mode: the human's value becomes the node output, read downstream as nodes.<id>.output
        return Updates.combine(
            Updates.set("status", NodeRunStatus.COMPLETED),
            Updates.set("output", input == null || input.isBlank() ? "{}" : input),
            Updates.set("completed_at", now));
    }

    /** The frozen graph the run executed: the run pins a version, the version holds the immutable graph snapshot. */
    public String getRunGraph(String runId, String userId) {
        WorkflowRun run = getRun(runId, userId);
        WorkflowPublishedVersion version = versionCollection.get(run.versionId)
            .orElseThrow(() -> new NotFoundException("workflow version not found for run: " + runId));
        return version.graph;
    }
}
