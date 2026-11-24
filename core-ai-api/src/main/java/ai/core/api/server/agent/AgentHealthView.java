package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Derived view of an agent's current reference integrity and latest author-side quality result.
 * It is computed at read time; it is not persisted on the agent document.
 *
 * @author core-ai
 */
public class AgentHealthView {
    @Property(name = "status")
    public String status;

    @Property(name = "needs_attention")
    public Boolean needsAttention;

    @Property(name = "reference_issues")
    public List<AgentReferenceIssueView> referenceIssues;

    @Property(name = "quality")
    public AgentQualityStatusView quality;
}
