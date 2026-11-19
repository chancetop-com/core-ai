package ai.core.api.server.quality;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class AddQualityExemptionRequest {
    /** {@code AGENT} or {@code SKILL}. */
    @Property(name = "type")
    public String type;

    @Property(name = "subject_id")
    public String subjectId;

    /** Optimistic guard: must match the active policy generation of the selected subject type. */
    @Property(name = "expected_generation")
    public Integer expectedGeneration;
}
