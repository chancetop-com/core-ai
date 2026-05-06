package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentCard {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "version")
    public String version;

    @Property(name = "supportedInterfaces")
    public List<AgentInterface> supportedInterfaces;

    @Property(name = "provider")
    public AgentProvider provider;

    @Property(name = "capabilities")
    public AgentCapabilities capabilities;

    @Property(name = "skills")
    public List<Skill> skills;

    @Property(name = "defaultInputModes")
    public List<String> defaultInputModes;

    @Property(name = "defaultOutputModes")
    public List<String> defaultOutputModes;

    @Property(name = "securitySchemes")
    public Map<String, SecurityScheme> securitySchemes;

    @Property(name = "securityRequirements")
    public List<Map<String, List<String>>> securityRequirements;

    @Property(name = "signatures")
    public List<AgentCardSignature> signatures;

    @Property(name = "documentationUrl")
    public String documentationUrl;

    @Property(name = "iconUrl")
    public String iconUrl;

    public static class AgentProvider {
        @Property(name = "organization")
        public String organization;

        @Property(name = "url")
        public String url;
    }

    public static class AgentCapabilities {
        @Property(name = "streaming")
        public Boolean streaming;

        @Property(name = "pushNotifications")
        public Boolean pushNotifications;

        @Property(name = "stateTransitionHistory")
        public Boolean stateTransitionHistory;

        @Property(name = "extendedAgentCard")
        public Boolean extendedAgentCard;

        @Property(name = "extensions")
        public List<AgentExtension> extensions;
    }

    public static class AgentExtension {
        @Property(name = "uri")
        public String uri;

        @Property(name = "description")
        public String description;

        @Property(name = "required")
        public Boolean required;

        @Property(name = "params")
        public Map<String, Object> params;
    }

    public static class Skill {
        public static Skill of(String name, String description) {
            var skill = new Skill();
            skill.id = name;
            skill.name = name;
            skill.description = description;
            return skill;
        }

        @Property(name = "id")
        public String id;

        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "tags")
        public List<String> tags;

        @Property(name = "examples")
        public List<String> examples;

        @Property(name = "inputModes")
        public List<String> inputModes;

        @Property(name = "outputModes")
        public List<String> outputModes;
    }

    public static class AgentInterface {
        @Property(name = "url")
        public String url;

        @Property(name = "protocolBinding")
        public String protocolBinding;

        @Property(name = "protocolVersion")
        public String protocolVersion;

        @Property(name = "tenant")
        public String tenant;
    }

    public static class SecurityScheme {
        @Property(name = "type")
        public String type;

        @Property(name = "description")
        public String description;

        @Property(name = "name")
        public String name;

        @Property(name = "location")
        public String location;

        @Property(name = "scheme")
        public String scheme;

        @Property(name = "bearerFormat")
        public String bearerFormat;

        @Property(name = "openIdConnectUrl")
        public String openIdConnectUrl;

        @Property(name = "flows")
        public Map<String, Object> flows;
    }

    public static class AgentCardSignature {
        @Property(name = "protected")
        public String protectedHeader;

        @Property(name = "signature")
        public String signature;

        @Property(name = "header")
        public Map<String, Object> header;
    }
}
