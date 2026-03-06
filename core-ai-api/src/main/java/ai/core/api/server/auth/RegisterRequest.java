package ai.core.api.server.auth;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class RegisterRequest {
    @NotNull
    @Property(name = "email")
    public String email;

    @NotNull
    @Property(name = "password")
    public String password;

    @Property(name = "name")
    public String name;
}
