package ai.core.api.server.agent;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class AgentQualityPolicyBindingView {
    @Property(name = "rule_id")
    public String ruleId;

    @Property(name = "rule_revision")
    public Integer ruleRevision;

    @Property(name = "enforcement")
    public String enforcement;
}
