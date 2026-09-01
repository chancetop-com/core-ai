package ai.core.server.web.auth;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.session.SessionIdentity;
import core.framework.inject.Inject;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Response;
import core.framework.web.exception.ForbiddenException;

/**
 * Enforces RBAC action permissions declared via {@link PermissionsRequired} on
 * WebServiceImpl implementation methods (and {@code http().route(controller::method)}
 * method references). Runs after {@link AuthInterceptor} so the session identity
 * or bearer-key user is resolved. Permission checks read the session identity
 * permissions first (anyMatch semantics like fbr location-hub); requests without
 * a session (api keys) fall back to {@link PermissionService#has} which exempts
 * api users. Missing annotation on a protected route is rejected (fail-closed).
 *
 * @author stephen
 */
public class PermissionInterceptor implements Interceptor {
    @Inject
    PermissionService permissionService;
    @Inject
    SessionIdentity sessionIdentity;
    @Inject
    ChannelConfigStore channelConfigStore;

    public boolean authDisabled;

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        if (authDisabled) return invocation.proceed();
        var path = invocation.context().request().path();
        if (!path.startsWith("/api/")) return invocation.proceed();
        if (AuthInterceptor.isPublicPath(path) || AuthInterceptor.isAnonymousChannelPath(path, channelConfigStore)) {
            return invocation.proceed();
        }
        if (path.startsWith("/api/api-users")) {
            // api-user management surface is already scoped by AuthInterceptor (cmk_ manage key branch)
            return invocation.proceed();
        }
        if (path.startsWith("/api/api-tools/mcp")) {
            // MCP transport routes are bound in the core-ai module, so they cannot carry
            // @PermissionsRequired; authentication is enforced by AuthInterceptor
            return invocation.proceed();
        }
        var required = invocation.annotation(PermissionsRequired.class);
        if (required != null) {
            var userId = AuthContext.userId(invocation.context());
            if (!hasAny(userId, required.value())) {
                throw new ForbiddenException("permission required: " + String.join(" or ", required.value()));
            }
            return invocation.proceed();
        }
        if (invocation.annotation(PermissionsBypass.class) != null) {
            return invocation.proceed();
        }
        throw new ForbiddenException("permission not declared for route: " + path);
    }

    private boolean hasAny(String userId, String[] required) {
        if (sessionIdentity.hasAny(required)) return true;
        for (var permission : required) {
            if (permissionService.has(userId, permission)) return true;
        }
        return false;
    }
}
