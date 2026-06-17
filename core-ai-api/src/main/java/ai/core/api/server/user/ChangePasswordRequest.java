package ai.core.api.server.user;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ChangePasswordRequest {
    @Property(name = "current_password")
    public String currentPassword;

    @Property(name = "new_password")
    public String newPassword;
}
