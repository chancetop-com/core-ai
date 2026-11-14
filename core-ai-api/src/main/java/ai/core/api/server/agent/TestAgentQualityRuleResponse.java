package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class TestAgentQualityRuleResponse {
    @Property(name = "rule_id")
    public String ruleId;

    @Property(name = "rule_revision")
    public Integer ruleRevision;

    @Property(name = "result")
    public String result;

    @Property(name = "matches_expected")
    public Boolean matchesExpected;

    @Property(name = "issues")
    public List<AgentQualityIssueView> issues;

    @Property(name = "duration_ms")
    public Long durationMs;

    @Property(name = "route_fingerprint")
    public String routeFingerprint;

    @Property(name = "lifecycle")
    public String lifecycle;
}
