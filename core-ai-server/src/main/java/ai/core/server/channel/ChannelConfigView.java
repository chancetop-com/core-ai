package ai.core.server.channel;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.util.Map;

/**
 * @author stephen
 */
@Collection(name = "channels")
public class ChannelConfigView {
    @Id
    public String channelId;
    @Field(name = "channel_type")
    public String channelType;
    @Field(name = "enabled")
    public Boolean enabled;
    @Field(name = "agent_id")
    public String agentId;
    @Field(name = "user_id")
    public String userId;
    @NotNull
    @Field(name = "require_auth")
    public Boolean requireAuth = Boolean.TRUE;
    @Field(name = "config")
    public Map<String, String> config;
    @Field(name = "filter_config")
    public Map<String, String> filterConfig;
    @Field(name = "session_ttl_minutes")
    public Integer sessionTtlMinutes;
}
