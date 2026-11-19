package ai.core.api.server.quality;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class QualityExemptionListResponse {
    @Property(name = "agent")
    public QualityExemptionGroupView agent;

    @Property(name = "skill")
    public QualityExemptionGroupView skill;
}
