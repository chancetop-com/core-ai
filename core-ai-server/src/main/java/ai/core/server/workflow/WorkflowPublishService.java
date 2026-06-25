package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes a workflow draft into an immutable {@link WorkflowPublishedVersion}: parse the draft graph, validate
 * it (structural + type + dominator), embed a snapshot of each referenced Agent's published config (anti-drift),
 * freeze it with a sha256, bump the version, and point the definition at it. Mirrors AgentDefinitionService.publish.
 * The runtime only ever loads a published version, never the draft.
 *
 * @author Xander
 */
public class WorkflowPublishService {
    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    public WorkflowPublishedVersion publish(String definitionId, String publishedBy) {
        WorkflowDefinition definition = definitionCollection.get(definitionId)
            .orElseThrow(() -> new IllegalStateException("workflow not found: " + definitionId));
        WorkflowPublishedVersion published = createVersion(definition, publishedBy, false);

        definition.publishedVersionId = published.id;
        definition.publishedVersion = published.version;
        definition.updatedAt = ZonedDateTime.now();
        definitionCollection.replace(definition);
        return published;
    }

    /** Snapshot the draft into an immutable version WITHOUT promoting it — used to run the draft (preview). */
    public WorkflowPublishedVersion createPreviewVersion(String definitionId, String userId) {
        WorkflowDefinition definition = definitionCollection.get(definitionId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + definitionId));
        if (!definition.userId.equals(userId)) {
            throw new ForbiddenException("workflow does not belong to the current user: " + definitionId);
        }
        return createVersion(definition, userId, true);
    }

    // Validate the draft, capture agent snapshots, freeze with a sha256 and insert an immutable version. Preview
    // versions get version=0 + preview=true and a uuid id, so they never inflate the real version counter.
    private WorkflowPublishedVersion createVersion(WorkflowDefinition definition, String publishedBy, boolean preview) {
        Map<String, String> agentSnapshots = new LinkedHashMap<>();
        List<String> errors = collectErrors(definition, agentSnapshots);
        if (!errors.isEmpty()) {
            throw new WorkflowValidationException(errors);
        }

        var published = new WorkflowPublishedVersion();
        if (preview) {
            published.id = definition.id + ":preview:" + UUID.randomUUID();
            published.version = 0;
            published.preview = true;
        } else {
            int version = nextVersion(definition.id);
            published.id = definition.id + ":v" + version;
            published.version = version;
            published.preview = false;
        }
        published.workflowId = definition.id;
        published.graph = definition.draftGraph;
        published.sha256 = WorkflowSha.hex(definition.draftGraph);
        published.envVars = Map.of();         // typed env vars land with the variable model (P2)
        published.agentSnapshots = agentSnapshots;
        published.toolDigests = Map.of();
        published.publishedBy = publishedBy;
        published.publishedAt = ZonedDateTime.now();
        versionCollection.insert(published);
        return published;
    }

    /** Validate a draft without publishing (the editor's Validate button). Returns all errors, empty if valid. */
    public List<String> validate(WorkflowDefinition definition) {
        return collectErrors(definition, new LinkedHashMap<>());
    }

    // Parse + structural/type/dominator validation + agent-snapshot validation; fills snapshots as a side effect.
    private List<String> collectErrors(WorkflowDefinition definition, Map<String, String> snapshots) {
        WorkflowGraph graph = WorkflowGraphParser.parse(definition.draftGraph);
        List<String> errors = new ArrayList<>(WorkflowValidator.validate(graph));
        captureAgentSnapshots(graph, definition.userId, errors, snapshots);
        captureWorkflowNodeErrors(graph, definition.id, definition.userId, errors);
        return errors;
    }

    // A WORKFLOW node calls a child workflow's pinned published version. Two cases are not publishable: a direct
    // self-reference (infinite recursion) and a child whose published graph contains a HUMAN_INPUT node — a parked
    // human input in the child would suspend and strand the parent's WORKFLOW node forever. A missing version_id is
    // left to the required-config error path elsewhere, so we only run the human-input check when it is present.
    private void captureWorkflowNodeErrors(WorkflowGraph graph, String workflowId, String ownerUserId, List<String> errors) {
        //todo: indirect/transitive cycle detection (A->B->A across published versions) is deferred to a
        // best-effort UI hint per design §5.2; the runtime child-depth cap is the backstop for now.
        for (WorkflowNode node : graph.nodes()) {
            if (!"WORKFLOW".equals(node.type())) {
                continue;
            }
            String sourceWorkflowId = configValue(node, "source_workflow_id");
            if (sourceWorkflowId == null) {
                continue;
            }
            if (workflowId.equals(sourceWorkflowId)) {
                errors.add("node " + node.id() + " (WORKFLOW) cannot reference its own workflow");
                continue;
            }
            WorkflowDefinition childDefinition = definitionCollection.get(sourceWorkflowId).orElse(null);
            if (childDefinition == null) {
                errors.add("node " + node.id() + " (WORKFLOW) references an unknown workflow: " + sourceWorkflowId);
                continue;
            }
            if (!isWorkflowAccessible(childDefinition, ownerUserId)) {
                errors.add("node " + node.id() + " (WORKFLOW) references a workflow that is not published or not accessible: " + sourceWorkflowId);
                continue;
            }
            String versionId = configValue(node, "version_id");
            if (versionId == null) {
                continue;
            }
            WorkflowPublishedVersion childVersion = versionCollection.get(versionId).orElse(null);
            if (childVersion == null) {
                errors.add("node " + node.id() + " (WORKFLOW) references an unknown workflow version: " + versionId);
                continue;
            }
            if (!sourceWorkflowId.equals(childVersion.workflowId)) {
                errors.add("node " + node.id() + " (WORKFLOW) version does not belong to workflow: " + sourceWorkflowId);
                continue;
            }
            if (Boolean.TRUE.equals(childVersion.preview)) {
                errors.add("node " + node.id() + " (WORKFLOW) cannot reference a preview workflow version: " + versionId);
                continue;
            }
            if (childVersion != null && childRequiresHumanInput(childVersion)) {
                errors.add("node " + node.id() + " (WORKFLOW) references a workflow that requires human input, which is not callable");
            }
        }
    }

    private boolean childRequiresHumanInput(WorkflowPublishedVersion childVersion) {
        WorkflowGraph childGraph = WorkflowGraphParser.parse(childVersion.graph);
        for (WorkflowNode childNode : childGraph.nodes()) {
            if ("HUMAN_INPUT".equals(childNode.type())) {
                return true;
            }
        }
        return false;
    }

    // Read a node config value as a non-blank String, treating null/blank/"null" as absent.
    private static String configValue(WorkflowNode node, String key) {
        Object raw = node.config().get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.isBlank() || "null".equals(value) ? null : value;
    }

    // Embed each AGENT/LLM node's referenced Agent published config; referencing an unknown, inaccessible or
    // unpublished definition is a publish error (the workflow version must be self-contained, reproducible, and
    // must not leak another tenant's config).
    private void captureAgentSnapshots(WorkflowGraph graph, String ownerUserId, List<String> errors, Map<String, String> snapshots) {
        for (WorkflowNode node : graph.nodes()) {
            if (!"AGENT".equals(node.type()) && !"LLM".equals(node.type())) {
                continue;
            }
            Object agentId = node.config().get("agent_id");
            if (agentId == null) {
                errors.add("node " + node.id() + " is missing agent_id");
                continue;
            }
            AgentDefinition agent = agentDefinitionCollection.get(String.valueOf(agentId)).orElse(null);
            if (agent == null) {
                errors.add("node " + node.id() + " references an unknown agent/LLM definition: " + agentId);
            } else if (!isAccessible(agent, ownerUserId)) {
                errors.add("node " + node.id() + " references an agent/LLM definition you do not own: " + agentId);
            } else if (agent.publishedConfig == null) {
                errors.add("node " + node.id() + " references an unpublished agent/LLM definition: " + agentId);
            } else {
                snapshots.put(node.id(), JSON.toJSON(agent.publishedConfig));
            }
        }
    }

    // A node may reference: its owner's agents, any system-default agent, OR any PUBLISHED agent (published == public,
    // matching the Explore/clone sharing model). The published config is then frozen into the workflow snapshot
    // (anti-drift). Referencing another user's UNPUBLISHED/private agent stays disallowed (falls through to "you do
    // not own"), and an owner referencing their own still-unpublished agent is caught by the publishedConfig == null check.
    private static boolean isAccessible(AgentDefinition agent, String ownerUserId) {
        return Boolean.TRUE.equals(agent.systemDefault)
            || (agent.userId != null && agent.userId.equals(ownerUserId))
            || agent.publishedConfig != null;
    }

    private static boolean isWorkflowAccessible(WorkflowDefinition definition, String ownerUserId) {
        return (definition.userId != null && definition.userId.equals(ownerUserId))
            || definition.publishedVersionId != null;
    }

    private int nextVersion(String workflowId) {
        int max = 0;
        for (WorkflowPublishedVersion existing : versionCollection.find(Filters.eq("workflow_id", workflowId))) {
            if (existing.version != null && !Boolean.TRUE.equals(existing.preview)) {
                max = Math.max(max, existing.version);
            }
        }
        return max + 1;
    }
}
