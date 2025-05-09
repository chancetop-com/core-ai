package ai.core.a2a;

import ai.core.agent.ImageAgent;
import ai.core.agent.Node;
import core.framework.json.JSON;

import java.util.List;

/**
 * @author stephen
 */
public class AgentCard {
    public static AgentCard of(Node<?> agent, AgentCardDTO dto) {
        var card = new AgentCard();
        card.name = agent.getName();
        card.description = agent.getDescription();
        card.url = dto.url;
        card.provider = new Provider();
        card.provider.organization = dto.providerOrganization;
        card.provider.url = dto.providerUrl;
        card.version = dto.version;
        card.documentationUrl = dto.documentationUrl;
        card.capabilities = new Capability();
        card.capabilities.streaming = false;
        card.capabilities.pushNotification = true;
        card.capabilities.stateTransitionHistory = true;
        card.authentication = dto.authentication;
        card.defaultInputModes = List.of("text");
        card.defaultOutputModes = agent instanceof ImageAgent ? List.of("image") : List.of("text");
        card.skills = dto.skills;
        return card;
    }

    public String name;
    public String description;
    public String url;
    public Provider provider;
    public String version;
    public String documentationUrl;
    public Capability capabilities;
    public Authentication authentication;
    public List<String> defaultInputModes;
    public List<String> defaultOutputModes;
    public List<Skill> skills;

    @Override
    public String toString() {
        return JSON.toJSON(this);
    }

    public static class Provider {
        public String organization;
        public String url;
    }

    public static class Capability {
        public Boolean streaming;
        public Boolean pushNotification;
        public Boolean stateTransitionHistory;
    }

    public static class Authentication {
        public List<String> schemes;
        public String credentials;
    }

    public static class Skill {
        public String id;
        public String name;
        public String description;
        public List<String> tags;
        public List<String> examples;
        public List<String> inputModes;
        public List<String> outputModes;
    }

    public record AgentCardDTO(
            String version,
            String url,
            String providerOrganization,
            String providerUrl,
            String documentationUrl,
            Authentication authentication,
            List<Skill> skills) {
    }
}
