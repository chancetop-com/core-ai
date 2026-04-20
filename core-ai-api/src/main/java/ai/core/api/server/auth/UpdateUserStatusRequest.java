package ai.core.api.server.auth;

import core.framework.api.json.Property;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public class UpdateUserStatusRequest {
    @Property(name = "email")
    public String email;

    @Property(name = "status")
    public String status;
}