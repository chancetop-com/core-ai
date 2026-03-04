package ai.core.api.server.user;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class UserView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "has_api_key")
    public Boolean hasApiKey;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "last_login_at")
    public ZonedDateTime lastLoginAt;
}
