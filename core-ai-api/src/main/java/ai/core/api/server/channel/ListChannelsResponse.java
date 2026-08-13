package ai.core.api.server.channel;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListChannelsResponse {
    @Property(name = "channels")
    public List<ChannelConfigView> channels;
}
