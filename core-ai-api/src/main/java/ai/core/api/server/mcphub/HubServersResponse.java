package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class HubServersResponse {
    @Property(name = "servers")
    public List<HubServerView> servers;
}
