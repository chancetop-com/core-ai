package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class ApiUserView {
    @NotNull
    @Property(name = "user_id")
    public String userId;

    @Property(name = "external_id")
    public String externalId;

    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "status")
    public String status;

    @Property(name = "permissions")
    public List<ResourcePermissionView> permissions;

    @Property(name = "quota")
    public ApiUserQuotaView quota;
}
