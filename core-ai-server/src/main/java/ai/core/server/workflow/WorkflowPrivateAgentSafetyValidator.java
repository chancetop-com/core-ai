package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSnapshotSource;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.WorkflowPublishedVersion;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;
import java.util.List;

/**
 * Rejects owner-bound executable resources before a Workflow Version becomes public.
 *
 * @author Xander
 */
public class WorkflowPrivateAgentSafetyValidator {
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    public List<String> validate(WorkflowPublishedVersion version) {
        if (version.agentSnapshotSources == null) {
            return List.of();
        }
        var errors = new ArrayList<String>();
        if (version.agentSnapshots != null) {
            for (String nodeId : version.agentSnapshots.keySet()) {
                if (!version.agentSnapshotSources.containsKey(nodeId)) {
                    errors.add("node " + nodeId + " is missing agent snapshot source metadata");
                }
            }
        }
        for (var entry : version.agentSnapshotSources.entrySet()) {
            validateSnapshot(version, entry.getKey(), entry.getValue(), errors);
        }
        return errors;
    }

    private boolean isMissingJsonValue(String json) {
        return json == null || json.isBlank() || "null".equals(json.trim());
    }

    private void validateSnapshot(WorkflowPublishedVersion version, String nodeId, String sourceJson,
                                  List<String> errors) {
        if (isMissingJsonValue(sourceJson)) {
            errors.add("node " + nodeId + " has malformed agent snapshot source metadata");
            return;
        }
        AgentSnapshotSource source;
        try {
            source = JSON.fromJSON(AgentSnapshotSource.class, sourceJson);
        } catch (RuntimeException e) {
            errors.add("node " + nodeId + " has malformed agent snapshot source metadata");
            return;
        }
        if (source == null || !"OWNED_EDITABLE".equals(source.sourceKind) && !"PUBLISHED".equals(source.sourceKind)) {
            errors.add("node " + nodeId + " has malformed agent snapshot source metadata");
            return;
        }
        if (version.agentSnapshots == null || !version.agentSnapshots.containsKey(nodeId)) {
            errors.add("node " + nodeId + " is missing an agent snapshot");
            return;
        }
        String snapshotJson = version.agentSnapshots.get(nodeId);
        if ("PUBLISHED".equals(source.sourceKind)) {
            if (isMissingJsonValue(snapshotJson)) {
                errors.add("node " + nodeId + " has a malformed agent snapshot");
            }
            return;
        }
        if (isMissingJsonValue(snapshotJson)) {
            errors.add("node " + nodeId + " has a malformed private agent snapshot");
            return;
        }
        try {
            AgentPublishedConfig config = JSON.fromJSON(AgentPublishedConfig.class, snapshotJson);
            if (config == null) {
                throw new IllegalArgumentException("null private agent snapshot");
            }
            validateConfig(nodeId, config, errors);
        } catch (RuntimeException e) {
            errors.add("node " + nodeId + " has a malformed private agent snapshot");
        }
    }

    private void validateConfig(String nodeId, AgentPublishedConfig config, List<String> errors) {
        if (config.systemPromptId != null && !config.systemPromptId.isBlank()) {
            errors.add("node " + nodeId + " private agent snapshot contains a system prompt reference");
        }
        if (config.skillIds != null && !config.skillIds.isEmpty()) {
            errors.add("node " + nodeId + " private agent snapshot contains skills");
        }
        if (config.datasetConfig != null && !config.datasetConfig.isEmpty()) {
            errors.add("node " + nodeId + " private agent snapshot contains dataset access");
        }
        if (Boolean.TRUE.equals(config.enableMemory)) {
            errors.add("node " + nodeId + " private agent snapshot enables memory");
        }
        if (hasUnavailableSubAgent(config)) {
            errors.add("node " + nodeId + " private agent snapshot requires unavailable sub-agents");
        }
        if (config.tools != null && config.tools.stream().anyMatch(tool -> tool == null
            || tool.type != ToolSourceType.BUILTIN && tool.type != ToolSourceType.API)) {
            errors.add("node " + nodeId + " private agent snapshot contains a disallowed tool type");
        }
        if (config.sandboxConfig != null
            && config.sandboxConfig.environmentVariables != null
            && !config.sandboxConfig.environmentVariables.isEmpty()) {
            errors.add("node " + nodeId + " private agent snapshot contains sandbox environment variables");
        }
        if (config.sandboxConfig != null
            && config.sandboxConfig.gitRepoUrl != null
            && !config.sandboxConfig.gitRepoUrl.isBlank()) {
            errors.add("node " + nodeId + " private agent snapshot contains a sandbox git repository");
        }
    }

    private boolean hasUnavailableSubAgent(AgentPublishedConfig config) {
        if (config.subAgentIds == null || config.subAgentIds.isEmpty()) {
            return false;
        }
        for (String subAgentId : config.subAgentIds) {
            if (subAgentId == null || subAgentId.isBlank() || agentDefinitionCollection == null) {
                return true;
            }
            try {
                AgentDefinition subAgent = agentDefinitionCollection.get(subAgentId).orElse(null);
                if (!WorkflowAgentAccessPolicy.hasUsablePublishedConfig(subAgent)) {
                    return true;
                }
            } catch (RuntimeException e) {
                return true;
            }
        }
        return false;
    }
}
