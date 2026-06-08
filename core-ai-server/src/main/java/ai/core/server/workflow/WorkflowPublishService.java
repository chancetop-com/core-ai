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
        return errors;
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

    // Mirror AgentDefinitionService's own-OR-system_default access rule.
    private static boolean isAccessible(AgentDefinition agent, String ownerUserId) {
        return Boolean.TRUE.equals(agent.systemDefault) || (agent.userId != null && agent.userId.equals(ownerUserId));
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
