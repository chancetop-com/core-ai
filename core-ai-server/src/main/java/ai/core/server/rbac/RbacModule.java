package ai.core.server.rbac;

import ai.core.api.server.rbac.RbacWebService;
import core.framework.module.Module;

/**
 * RBAC role configuration module. {@link RoleRegistry} is bound in ApiUserModule
 * (before {@code PermissionService}, which depends on it); this module only
 * registers the admin role config web service.
 *
 * @author stephen
 */
public class RbacModule extends Module {
    @Override
    protected void initialize() {
        api().service(RbacWebService.class, bind(RbacWebServiceImpl.class));
    }
}
