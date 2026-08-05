package ai.core.server.workflow;

import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSnapshotSource;
import ai.core.server.domain.DefinitionType;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;

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

    @Inject
    SkillService skillService;

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

            SnapshotCandidate candidate = snapshotCandidate(node, agent, ownerUserId, errors);
            if (candidate == null) continue;
            AgentPublishedConfig config = candidate.config;

            if (config.tools != null
                && config.tools.stream().anyMatch(AgentDependencyAccessPolicy::isLlmCallRef)) {
                errors.add("node " + node.id()
                    + " agent snapshot contains an unsupported LLM call tool dependency");
                continue;
            }
            if (!hasUsablePublishedSubAgents(node, config, errors)) {
                continue;
            }

            snapshots.put(node.id(), JSON.toJSON(config));
            var source = new AgentSnapshotSource();
            source.agentId = agent.id;
            source.sourceKind = candidate.sourceKind;
            source.sourceUpdatedAt = agent.updatedAt;
            source.capturedAt = ZonedDateTime.now();
            sources.put(node.id(), JSON.toJSON(source));
        }
    }

    private SnapshotCandidate snapshotCandidate(WorkflowNode node, AgentDefinition agent,
                                                String ownerUserId, List<String> errors) {
        if (WorkflowAgentAccessPolicy.isOwnedEditable(agent, ownerUserId)) {
            var config = AgentExecutableConfigFactory.fromEditableDefinition(agent);
            if (!hasAccessibleSkills(node, config, ownerUserId, errors)) return null;
            AgentDependencyAccessPolicy.markPublishedSkillsValidated(config);
            return new SnapshotCandidate(config, "OWNED_EDITABLE");
        }
        if (WorkflowAgentAccessPolicy.hasUsablePublishedConfig(agent)) {
            return new SnapshotCandidate(
                AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig), "PUBLISHED");
        }
        errors.add("node " + node.id() + " references an agent/LLM definition you cannot access: " + agent.id);
        return null;
    }

    private boolean hasAccessibleSkills(WorkflowNode node, AgentPublishedConfig config,
                                        String ownerUserId, List<String> errors) {
        var skillIds = IdLists.clean(config.skillIds);
        if (skillIds.isEmpty()) return true;
        try {
            skillService.resolveAccessibleSkills(skillIds, ownerUserId);
            return true;
        } catch (ForbiddenException e) {
            errors.add("node " + node.id() + " references an unavailable skill dependency");
            return false;
        }
    }

    private boolean hasUsablePublishedSubAgents(WorkflowNode node, AgentPublishedConfig config,
                                                List<String> errors) {
        if (config.subAgentIds == null) {
            return true;
        }
        for (String subAgentId : config.subAgentIds) {
            AgentDefinition subAgent = agentDefinitionCollection.get(subAgentId).orElse(null);
            if (!WorkflowAgentAccessPolicy.hasUsablePublishedSubAgent(subAgent)) {
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

    private record SnapshotCandidate(AgentPublishedConfig config, String sourceKind) {
    }
}
