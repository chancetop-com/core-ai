package ai.core.api.server.auth;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ResetUserPasswordRequest {
    @Property(name = "email")
    public String email;

    @Property(name = "password")
    public String password;
}
