package ai.core.server.workflow;

import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSnapshotSource;
import ai.core.server.domain.DefinitionType;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Freezes executable Agent configs and their provenance into a workflow version.
 *
 * @author Xander
 */
public class WorkflowAgentSnapshotService {
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    public void capture(WorkflowGraph graph, String ownerUserId, List<String> errors,
                        Map<String, String> snapshots, Map<String, String> sources) {
        for (WorkflowNode node : graph.nodes()) {
            if (!"AGENT".equals(node.type()) && !"LLM".equals(node.type())) {
                continue;
            }
            String agentId = configValue(node, "agent_id");
            if (agentId == null) {
                errors.add("node " + node.id() + " is missing agent_id");
                continue;
            }
            AgentDefinition agent = agentDefinitionCollection.get(agentId).orElse(null);
            if (agent == null) {
                errors.add("node " + node.id() + " references an unknown agent/LLM definition: " + agentId);
                continue;
            }
            DefinitionType expectedType = "LLM".equals(node.type()) ? DefinitionType.LLM_CALL : DefinitionType.AGENT;
            if (agent.type != expectedType) {
                errors.add("node " + node.id() + " references an agent with the wrong type: " + agent.id);
                continue;
            }

            AgentPublishedConfig config;
            String sourceKind;
            if (WorkflowAgentAccessPolicy.isOwnedEditable(agent, ownerUserId)) {
                if (!hasUsablePublishedSubAgents(node, agent, errors)) {
                    continue;
                }
                config = AgentExecutableConfigFactory.fromEditableDefinition(agent);
                sourceKind = "OWNED_EDITABLE";
            } else if (WorkflowAgentAccessPolicy.hasUsablePublishedConfig(agent)) {
                config = AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig);
                sourceKind = "PUBLISHED";
            } else {
                errors.add("node " + node.id() + " references an agent/LLM definition you cannot access: " + agent.id);
                continue;
            }

            snapshots.put(node.id(), JSON.toJSON(config));
            var source = new AgentSnapshotSource();
            source.agentId = agent.id;
            source.sourceKind = sourceKind;
            source.sourceUpdatedAt = agent.updatedAt;
            source.capturedAt = ZonedDateTime.now();
            sources.put(node.id(), JSON.toJSON(source));
        }
    }

    private boolean hasUsablePublishedSubAgents(WorkflowNode node, AgentDefinition agent, List<String> errors) {
        if (agent.subAgentIds == null) {
            return true;
        }
        for (String subAgentId : agent.subAgentIds) {
            AgentDefinition subAgent = agentDefinitionCollection.get(subAgentId).orElse(null);
            if (!WorkflowAgentAccessPolicy.hasUsablePublishedConfig(subAgent)) {
                errors.add("node " + node.id() + " references sub-agent without a usable published config: " + subAgentId);
                return false;
            }
        }
        return true;
    }

    private String configValue(WorkflowNode node, String key) {
        Object raw = node.config().get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.isBlank() || "null".equals(value) ? null : value;
    }
}
