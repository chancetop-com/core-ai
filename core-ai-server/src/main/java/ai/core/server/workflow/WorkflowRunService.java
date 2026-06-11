package ai.core.server.workflow;

import ai.core.api.server.workflow.PendingInputView;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
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
import java.util.UUID;

/**
 * Creates and reads workflow runs. createRun pins the latest published version and inserts a PENDING run with
 * lease_until=now so the WorkflowRunnerJob claims and drives it on the next tick (same path as crash recovery).
 *
 * @author Xander
 */
public class WorkflowRunService {
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
        if (!definition.userId.equals(userId)) {
            throw new ForbiddenException("workflow does not belong to the current user: " + workflowId);
        }
        if (definition.publishedVersionId == null) {
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
     * Settle a HUMAN_INPUT node a paused run is waiting on, then wake the run. For mode=approval the human's
     * approve/reject picks the node's approve/reject out-edge (Branch); for mode=input the human's value becomes
     * the node output. Flips the run PAUSED -> PENDING so the runner (caller submits) re-folds and drives downstream.
     */
    public void resume(String runId, String nodeId, Boolean approve, String input, String userId) {
        WorkflowRun run = getRun(runId, userId);   // ownership
        if (run.status != RunStatus.PAUSED) {
            throw new ConflictException("workflow run is not paused: " + runId);
        }
        WorkflowNode node = resolveHumanNode(run, nodeId);
        HumanInputProtocol.validate(node, approve, input);   // mode + form schema enforced before settling
        long settled = nodeRunCollection.update(
            Filters.and(Filters.eq("run_id", runId), Filters.eq("node_id", nodeId), Filters.eq("status", NodeRunStatus.WAITING)),
            settleHumanNode(node, approve, input));
        if (settled == 0) {
            throw new ConflictException("node is not awaiting human input (already settled by a concurrent resume?): " + nodeId);
        }
        // wake: PAUSED -> PENDING, claimable now (guarded on PAUSED so a concurrent resume only wakes once)
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
