package ai.core.server.seoops;

import java.util.Map;

/**
 * @author xander
 */
public record SeoOpsRuntimeConfig(boolean enabled, String copilotAgentId) {
    static SeoOpsRuntimeConfig from(Map<String, String> properties) {
        var enabled = "true".equalsIgnoreCase(properties.getOrDefault("sys.seoops.enabled", "false"));
        var rawAgentId = properties.get("sys.seoops.copilot.agent-id");
        var agentId = rawAgentId == null || rawAgentId.isBlank() ? null : rawAgentId.trim();
        return new SeoOpsRuntimeConfig(enabled, agentId);
    }
}
