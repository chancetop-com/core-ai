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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        WorkflowGraph graph = WorkflowGraphParser.parse(definition.draftGraph);
        List<String> errors = new ArrayList<>(WorkflowValidator.validate(graph));
        Map<String, String> agentSnapshots = new LinkedHashMap<>();
        captureAgentSnapshots(graph, errors, agentSnapshots);
        if (!errors.isEmpty()) {
            throw new WorkflowValidationException(errors);
        }

        var now = ZonedDateTime.now();
        int version = nextVersion(definition.id);
        var published = new WorkflowPublishedVersion();
        published.id = definition.id + ":v" + version;
        published.workflowId = definition.id;
        published.version = version;
        published.graph = definition.draftGraph;
        published.sha256 = WorkflowSha.hex(definition.draftGraph);
        published.envVars = Map.of();         // typed env vars land with the variable model (P2)
        published.agentSnapshots = agentSnapshots;
        published.toolDigests = Map.of();
        published.publishedBy = publishedBy;
        published.publishedAt = now;
        versionCollection.insert(published);

        definition.publishedVersionId = published.id;
        definition.publishedVersion = version;
        definition.updatedAt = now;
        definitionCollection.replace(definition);
        return published;
    }

    // Embed each AGENT/LLM node's referenced Agent published config; referencing an unknown or unpublished agent
    // is a publish error (the workflow version must be self-contained and reproducible).
    private void captureAgentSnapshots(WorkflowGraph graph, List<String> errors, Map<String, String> snapshots) {
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
                errors.add("node " + node.id() + " references unknown agent " + agentId);
            } else if (agent.publishedConfig == null) {
                errors.add("node " + node.id() + " references unpublished agent " + agentId);
            } else {
                snapshots.put(node.id(), JSON.toJSON(agent.publishedConfig));
            }
        }
    }

    private int nextVersion(String workflowId) {
        int max = 0;
        for (WorkflowPublishedVersion existing : versionCollection.find(Filters.eq("workflow_id", workflowId))) {
            if (existing.version != null) {
                max = Math.max(max, existing.version);
            }
        }
        return max + 1;
    }
}
