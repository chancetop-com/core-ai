package ai.core.api.server.channel;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ChannelTypeView {
    @Property(name = "type")
    public String type;

    @Property(name = "label")
    public String label;
}
