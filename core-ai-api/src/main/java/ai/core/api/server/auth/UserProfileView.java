package ai.core.api.server.auth;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Current user profile including RBAC action permissions.
 *
 * @author stephen
 */
public class UserProfileView {
    @Property(name = "user_id")
    public String userId;

    @Property(name = "name")
    public String name;

    @Property(name = "role")
    public String role;

    @Property(name = "permissions")
    public List<String> permissions;
}
