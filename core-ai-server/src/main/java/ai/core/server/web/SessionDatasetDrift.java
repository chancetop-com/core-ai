package ai.core.server.web;

import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.ChatSession;

import java.util.HashSet;
import java.util.List;

/**
 * Compares the dataset bindings a session captured at creation with the ones the agent would hand a new session.
 * The answer is a boolean, not the configs themselves: a client only needs to tell the user that the agent changed
 * and a new chat is needed, and the agent may hold data the session's caller is not allowed to read.
 *
 * @author stephen
 */
final class SessionDatasetDrift {
    static Boolean changed(ChatSession session, AgentDefinitionService agentDefinitionService) {
        if (session.agentId == null || session.agentId.isBlank()) return null;
        try {
            var definition = agentDefinitionService.getEntity(session.agentId);
            var executable = AgentDependencyAccessPolicy.executableSessionAgent(definition, session.userId);
            var current = AgentDefinitionService.resolveDatasetConfig(executable);
            return !sameBindings(session.datasetConfig, current);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // isOutput only picks the report target; a change to it does not change the session's tool set.
    static boolean sameBindings(List<AgentDatasetConfig> snapshot, List<AgentDatasetConfig> current) {
        var left = snapshot == null ? List.<AgentDatasetConfig>of() : snapshot;
        var right = current == null ? List.<AgentDatasetConfig>of() : current;
        if (left.size() != right.size()) return false;
        var keys = new HashSet<String>();
        for (var config : left) keys.add(bindingKey(config));
        for (var config : right) {
            if (!keys.remove(bindingKey(config))) return false;
        }
        return keys.isEmpty();
    }

    private static String bindingKey(AgentDatasetConfig config) {
        return config.datasetId + ":" + (config.permission != null ? config.permission.name() : "");
    }

    private SessionDatasetDrift() {
    }
}
