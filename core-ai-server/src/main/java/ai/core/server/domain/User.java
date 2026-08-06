package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
@Collection(name = "users")
public class User {
    @Id
    public String id;

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "email")
    public String email;

    @Field(name = "password_hash")
    public String passwordHash;

    @Field(name = "api_key")
    public String apiKey;

    @Field(name = "api_key_created_at")
    public ZonedDateTime apiKeyCreatedAt;

    @NotNull
    @Field(name = "role")
    public String role = "user";

    @NotNull
    @Field(name = "status")
    public String status = "pending";

    @NotNull
    @Field(name = "user_type")
    public String userType = "internal";

    @Field(name = "owner_id")
    public String ownerId;

    @Field(name = "external_id")
    public String externalId;

    @Field(name = "permissions")
    public List<ResourcePermission> permissions;

    @Field(name = "quota_tokens")
    public Long quotaTokens;

    @Field(name = "quota_window_start")
    public ZonedDateTime quotaWindowStart;

    @Field(name = "quota_consumed_tokens")
    public Long quotaConsumedTokens;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "last_login_at")
    public ZonedDateTime lastLoginAt;
}
