package ai.core.api.server.channel;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ChannelResponse {
    @Property(name = "channel")
    public ChannelConfigView channel;
}
