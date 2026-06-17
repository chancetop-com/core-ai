package ai.core.api.server.auth;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateUserRoleRequest {
    @Property(name = "email")
    public String email;

    @Property(name = "role")
    public String role;
}
