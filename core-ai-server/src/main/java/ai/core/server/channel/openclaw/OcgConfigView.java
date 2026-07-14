package ai.core.server.channel.openclaw;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "ocg_configs")
public class OcgConfigView {
    @Id
    public String id;

    @NotNull
    @Field(name = "channel_id")
    public String channelId;

    @NotNull
    @Field(name = "config_json")
    public String configJson;

    @Field(name = "callback_secret")
    public String callbackSecret;

    @NotNull
    @Field(name = "enabled")
    public Boolean enabled = Boolean.TRUE;

    @Field(name = "sandbox_id")
    public String sandboxId;

    @Field(name = "sandbox_ip")
    public String sandboxIp;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
