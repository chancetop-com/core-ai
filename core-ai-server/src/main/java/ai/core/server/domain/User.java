package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

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

    @Field(name = "api_key")
    public String apiKey;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "last_login_at")
    public ZonedDateTime lastLoginAt;
}
