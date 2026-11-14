package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ActivateAgentQualityPolicyRequest {
    @Property(name = "policy_revision")
    public Integer policyRevision;

    @Property(name = "rule_id")
    public String ruleId;

    @Property(name = "rule_revision")
    public Integer ruleRevision;

    @Property(name = "enforcement")
    public String enforcement;

    @Property(name = "expected_generation")
    public Integer expectedGeneration;

    @Property(name = "change_reason")
    public String changeReason;

    @Property(name = "suspend")
    public List<String> suspend;

    @Property(name = "resume")
    public List<String> resume;
}
