package ai.core.api.server.auth;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class DeleteUserRequest {
    @Property(name = "email")
    public String email;
}
