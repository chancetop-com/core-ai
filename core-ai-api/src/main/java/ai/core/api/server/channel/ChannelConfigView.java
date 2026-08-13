package ai.core.api.server.channel;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class ChannelConfigView {
    @Property(name = "channelId")
    public String channelId;

    @Property(name = "channelType")
    public String channelType;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "requireAuth")
    public Boolean requireAuth;

    @Property(name = "agentId")
    public String agentId;

    @Property(name = "userId")
    public String userId;

    @Property(name = "sessionTtlMinutes")
    public Integer sessionTtlMinutes;

    @Property(name = "config")
    public Map<String, String> config;

    @Property(name = "filterConfig")
    public Map<String, String> filterConfig;

    @Property(name = "webhookUrl")
    public String webhookUrl;
}
