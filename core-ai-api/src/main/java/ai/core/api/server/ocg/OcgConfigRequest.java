package ai.core.api.server.ocg;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class OcgConfigRequest {
    @Property(name = "id")
    public String id;

    @Property(name = "channelId")
    public String channelId;

    @Property(name = "configJson")
    public String configJson;

    @Property(name = "callbackSecret")
    public String callbackSecret;

    @Property(name = "enabled")
    public Boolean enabled;
}
