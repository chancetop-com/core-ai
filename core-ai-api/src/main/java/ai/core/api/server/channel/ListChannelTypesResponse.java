package ai.core.api.server.channel;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListChannelTypesResponse {
    @Property(name = "types")
    public List<ChannelTypeView> types;
}
