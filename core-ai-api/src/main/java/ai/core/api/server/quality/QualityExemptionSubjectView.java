package ai.core.api.server.quality;

import core.framework.api.json.Property;

/**
 * A selectable agent or skill in the exemption picker.
 *
 * @author core-ai
 */
public class QualityExemptionSubjectView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    /** The agent id or the skill qualified name, shown as the secondary line. */
    @Property(name = "detail")
    public String detail;

    @Property(name = "already_exempt")
    public Boolean alreadyExempt;

    /** Built-in system agents and personal assistant copies skip the gate without an entry. */
    @Property(name = "automatic")
    public Boolean automatic;
}
