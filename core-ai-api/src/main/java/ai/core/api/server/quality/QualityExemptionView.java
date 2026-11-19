package ai.core.api.server.quality;

import core.framework.api.json.Property;

/**
 * One whitelist entry as stored in the active quality policy revision. {@code value} is the raw
 * stored string used for matching; the resolved subject fields are best effort and stay null when
 * the subject no longer exists.
 *
 * @author core-ai
 */
public class QualityExemptionView {
    @Property(name = "value")
    public String value;

    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "name")
    public String name;

    /** Stable secondary line: the agent id or the skill qualified name. */
    @Property(name = "detail")
    public String detail;

    @Property(name = "resolved")
    public Boolean resolved;

    /** Built-in agents and personal assistant copies are exempt automatically; a matching entry is redundant. */
    @Property(name = "automatic")
    public Boolean automatic;
}
