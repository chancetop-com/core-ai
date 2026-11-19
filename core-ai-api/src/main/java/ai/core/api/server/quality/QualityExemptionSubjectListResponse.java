package ai.core.api.server.quality;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class QualityExemptionSubjectListResponse {
    @Property(name = "subjects")
    public List<QualityExemptionSubjectView> subjects;
}
