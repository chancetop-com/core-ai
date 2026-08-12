package ai.core.api.server.rbac;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * Admin RBAC role configuration.
 *
 * @author stephen
 */
public interface RbacWebService {
    @GET
    @Path("/api/admin/rbac/roles")
    RoleConfigView listRoles();

    @PUT
    @Path("/api/admin/rbac/roles")
    void updateRoles(RoleConfigView request);
}
