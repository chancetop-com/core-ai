package ai.core.server.workflow;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentExecutableConfigFactory;
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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorkflowAgentSnapshotService.class);

    @Inject
    AgentDefinitionStore agentDefinitionStore;

    @Inject
    SkillService skillService;

    @Inject
    ai.core.server.quality.PublishQualityGate qualityGate;

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
            AgentDefinition agent = agentDefinitionStore.get(agentId).orElse(null);
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
            var candidate = qualityGate.buildCandidate(agent);
            if (!hasAccessibleSkills(node, candidate.config(), ownerUserId, errors)) return null;
            try {
                qualityGate.evaluate(agent, candidate, ownerUserId,
                    ai.core.server.quality.domain.QualityGateMode.WORKFLOW, null, true);
            } catch (ai.core.server.quality.QualityGateException e) {
                errors.addAll(qualityIssues("node " + node.id(), e.report()));
                return null;
            } catch (RuntimeException e) {
                errors.add("node " + node.id() + " failed the publish quality check: " + e.getMessage());
                return null;
            }
            return new SnapshotCandidate(candidate.config(), "OWNED_EDITABLE");
        }
        if (WorkflowAgentAccessPolicy.hasUsablePublishedConfig(agent)) {
            verifyPublishedReceipt(node, agent);
            return new SnapshotCandidate(
                AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig), "PUBLISHED");
        }
        errors.add("node " + node.id() + " references an agent/LLM definition you cannot access: " + agent.id);
        return null;
    }

    private void verifyPublishedReceipt(WorkflowNode node, AgentDefinition agent) {
        var receipt = agent.qualityReceipt;
        if (receipt == null) {
            LOGGER.warn("workflow snapshot uses a published agent without a quality receipt, node={}, agent={}",
                node.id(), agent.id);
            return;
        }
        var configHash = ai.core.server.quality.QualityHashes.candidateHash(agent.publishedConfig);
        var metadataHash = ai.core.server.quality.QualityHashes.metadataHash(
            agent.publishedName != null ? agent.publishedName : agent.name,
            agent.publishedDescription != null ? agent.publishedDescription : agent.description);
        if (!configHash.equals(receipt.executionCandidateHash) || !metadataHash.equals(receipt.metadataHash)) {
            LOGGER.warn("workflow snapshot uses a published agent whose receipt does not match its published config, "
                + "node={}, agent={}", node.id(), agent.id);
        }
    }

    private List<String> qualityIssues(String prefix, ai.core.api.server.agent.AgentQualityReportView report) {
        if (report == null) return List.of(prefix + " failed the publish quality check");
        var errors = new java.util.ArrayList<String>();
        if (report.issues != null) {
            for (var issue : report.issues) {
                if (errors.size() >= 5) break;
                errors.add(prefix + ": " + issue.ruleId + "@" + issue.ruleRevision + " " + issue.message
                    + " 建议：" + issue.suggestion);
            }
        }
        if (errors.isEmpty()) errors.add(prefix + " failed the publish quality check");
        return errors;
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
            AgentDefinition subAgent = agentDefinitionStore.get(subAgentId).orElse(null);
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
