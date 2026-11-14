package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class AgentQualityPolicyHistoryResponse {
    @Property(name = "policies")
    public List<AgentQualityPolicyView> policies;
}
