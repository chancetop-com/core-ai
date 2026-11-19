package ai.core.api.server.quality;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class QualityExemptionGroupView {
    @Property(name = "policy_revision")
    public Integer policyRevision;

    @Property(name = "generation")
    public Integer generation;

    @Property(name = "entries")
    public List<QualityExemptionView> entries;
}
