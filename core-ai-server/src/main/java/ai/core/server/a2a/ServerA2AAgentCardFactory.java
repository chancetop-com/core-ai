package ai.core.server.a2a;

import ai.core.api.a2a.AgentCard;
import ai.core.api.a2a.A2ATransport;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps server agent definitions to A2A Agent Cards.
 *
 * @author xander
 */
final class ServerA2AAgentCardFactory {
    static AgentCard from(AgentDefinition definition) {
        var card = new AgentCard();
        card.name = definition.name;
        card.description = definition.description != null ? definition.description : "core-ai-server agent";
        card.version = definition.publishedAt != null ? definition.publishedAt.toInstant().toString() : "draft";
        var interfaceConfig = new AgentCard.AgentInterface();
        interfaceConfig.protocolBinding = A2ATransport.HTTP_JSON;
        interfaceConfig.protocolVersion = "1.0";
        interfaceConfig.url = "/api/a2a";
        interfaceConfig.tenant = definition.id;
        card.supportedInterfaces = List.of(interfaceConfig);
        var capabilities = new AgentCard.AgentCapabilities();
        capabilities.streaming = true;
        capabilities.pushNotifications = false;
        capabilities.stateTransitionHistory = false;
        card.capabilities = capabilities;
        card.skills = skills(definition);
        card.defaultInputModes = List.of("text/plain", "application/json");
        card.defaultOutputModes = List.of("text/plain", "application/json");
        return card;
    }

    private static List<ToolRef> effectiveTools(AgentDefinition definition) {
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null) {
            return definition.publishedConfig.tools;
        }
        return definition.tools;
    }

    private static List<AgentCard.Skill> skills(AgentDefinition definition) {
        var skills = new ArrayList<AgentCard.Skill>();
        var primary = AgentCard.Skill.of(definition.id, definition.description != null ? definition.description : definition.name);
        primary.name = definition.name;
        primary.inputModes = List.of("text/plain", "application/json");
        primary.outputModes = List.of("text/plain", "application/json");
        skills.add(primary);
        var toolRefs = effectiveTools(definition);
        if (toolRefs != null) {
            for (var tool : toolRefs) {
                if (tool == null || tool.id == null) continue;
                var skill = AgentCard.Skill.of(tool.id, "Server-side tool: " + tool.id);
                skill.tags = tool.type != null ? List.of(tool.type.name()) : null;
                skills.add(skill);
            }
        }
        return skills;
    }

    private ServerA2AAgentCardFactory() {
    }
}
