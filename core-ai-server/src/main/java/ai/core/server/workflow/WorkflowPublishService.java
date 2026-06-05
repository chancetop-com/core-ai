package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.workflow.engine.WorkflowGraph;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Publishes a workflow draft into an immutable {@link WorkflowPublishedVersion}: parse the draft graph,
 * validate it (structural + type + dominator), freeze it with a sha256, bump the version, and point the
 * definition at it. Mirrors AgentDefinitionService.publish. The runtime only ever loads a published version,
 * never the draft.
 *
 * @author Xander
 */
public class WorkflowPublishService {
    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    public WorkflowPublishedVersion publish(String definitionId, String publishedBy) {
        WorkflowDefinition definition = definitionCollection.get(definitionId)
            .orElseThrow(() -> new IllegalStateException("workflow not found: " + definitionId));

        WorkflowGraph graph = WorkflowGraphParser.parse(definition.draftGraph);
        List<String> errors = WorkflowValidator.validate(graph);
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
        published.agentSnapshots = Map.of();  // embedded Agent snapshots land with AGENT/LLM executors (1c)
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
