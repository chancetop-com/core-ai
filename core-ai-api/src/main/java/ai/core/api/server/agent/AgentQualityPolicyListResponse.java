package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class AgentQualityPolicyListResponse {
    @Property(name = "active")
    public AgentQualityPolicyView active;

    @Property(name = "history")
    public List<AgentQualityPolicyView> history;
}
