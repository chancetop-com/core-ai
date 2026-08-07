package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class AdminApiUserView {
    @NotNull
    @Property(name = "user_id")
    public String userId;

    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "status")
    public String status;

    @Property(name = "key_prefix")
    public String keyPrefix;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "last_used_at")
    public ZonedDateTime lastUsedAt;

    @Property(name = "owner_id")
    public String ownerId;

    @Property(name = "owner_name")
    public String ownerName;

    @Property(name = "created_by")
    public String createdBy;
}
