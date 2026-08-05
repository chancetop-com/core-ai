package ai.core.server.session;

import ai.core.api.server.session.SessionConfig;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.ToolRef;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class SessionState {
    public static final int CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION = 2;
    public static final int CURRENT_SANDBOX_BINDING_SECURITY_VERSION = 1;

    public static SessionState fromJson(String json) {
        if (Strings.isBlank(json)) return null;
        return JSON.fromJSON(SessionState.class, json);
    }

    public String sessionId;
    public String userId;
    public boolean fromAgent;
    /**
     * Marks agent snapshots that were created after caller-aware dependency validation.
     * Deliberately nullable so persisted legacy JSON remains distinguishable from trusted state.
     */
    public Integer agentSnapshotSecurityVersion;
    /** Positive provenance marker required before a persisted sandbox binding may be reattached. */
    public Integer sandboxBindingSecurityVersion;
    /** Full agent config snapshot — avoids needing AgentDefinitionService during rebuild. */
    public AgentConfigSnapshot agentConfig;
    public SessionConfig config;
    public List<ToolRef> tools;
    public List<String> skillIds;
    public List<String> subAgentIds;

    public String toJson() {
        return JSON.toJSON(this);
    }

    /**
     * Lightweight snapshot of agent definition, sufficient to rebuild a session.
     */
    public static class AgentConfigSnapshot {
        public String agentId;
        public String agentName;
        public String systemPrompt;
        public String systemPromptId;
        public String model;
        public String multiModalModel;
        public Boolean preferCaptionPath;
        public Double temperature;
        public String thinkingEffort;
        public Integer maxTurns;
        public String inputTemplate;
        public Map<String, String> variables;
        public List<ToolRef> tools;
        public List<String> skillIds;
        public List<AgentDatasetConfig> datasetConfig;
        public AgentSandboxConfig sandboxConfig;
    }
}
