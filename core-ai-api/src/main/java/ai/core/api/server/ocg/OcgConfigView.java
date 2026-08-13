package ai.core.api.server.ocg;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class OcgConfigView {
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

    @Property(name = "sandboxId")
    public String sandboxId;

    @Property(name = "sandboxIp")
    public String sandboxIp;

    @Property(name = "sandboxStatus")
    public String sandboxStatus;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;

    @Property(name = "updatedAt")
    public ZonedDateTime updatedAt;

    @Property(name = "channelName")
    public String channelName;
}
