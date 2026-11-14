package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.util.IdLists;
import core.framework.web.exception.BadRequestException;

/**
 * Fast, message-compatible dependency checks that predate the quality gate. The A1 rules re-check the same
 * facts against the publish candidate; keeping this layer avoids turning a known dependency error into a
 * later rule report for existing callers.
 *
 * @author core-ai
 */
final class AgentDependencyPrecheck {
    static void check(AgentDefinition entity, AgentDefinitionStore store) {
        requirePublishedLlmCallDependencies(entity, store);
        requirePublishedSubAgentDependencies(entity, store);
    }

    private static void requirePublishedLlmCallDependencies(AgentDefinition entity, AgentDefinitionStore store) {
        if (entity.type != DefinitionType.AGENT || entity.tools == null) return;
        for (var tool : entity.tools) {
            if (!AgentDependencyAccessPolicy.isLlmCallRef(tool)) continue;
            String definitionId;
            try {
                definitionId = AgentDependencyAccessPolicy.requireLlmCallDefinitionId(tool);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("agent has an unavailable LLM call dependency", "BAD_REQUEST", e);
            }
            AgentDefinition dependency = store.get(definitionId).orElse(null);
            if (!AgentDependencyAccessPolicy.hasUsablePublishedLlmCall(dependency)) {
                throw new BadRequestException("agent has an unavailable LLM call dependency");
            }
        }
    }

    private static void requirePublishedSubAgentDependencies(AgentDefinition entity, AgentDefinitionStore store) {
        if (entity.type != DefinitionType.AGENT) return;
        for (String subAgentId : IdLists.clean(entity.subAgentIds)) {
            AgentDefinition dependency = store.get(subAgentId).orElse(null);
            if (!AgentDependencyAccessPolicy.hasUsablePublishedSubAgent(dependency)) {
                throw new BadRequestException("agent has an unavailable sub-agent dependency");
            }
        }
    }

    private AgentDependencyPrecheck() {
    }
}
