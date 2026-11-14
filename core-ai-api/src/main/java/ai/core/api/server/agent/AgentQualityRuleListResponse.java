package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class AgentQualityRuleListResponse {
    @Property(name = "rules")
    public List<AgentQualityRuleView> rules;

    @Property(name = "policy_revision")
    public Integer policyRevision;
}
