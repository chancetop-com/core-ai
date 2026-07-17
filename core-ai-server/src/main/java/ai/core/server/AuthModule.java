package ai.core.server;

import ai.core.api.server.auth.AuthWebService;
import ai.core.server.auth.AuthService;
import ai.core.server.web.AuthWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AuthModule extends Module {
    @Override
    protected void initialize() {
        var authService = bind(AuthService.class);
        authService.adminEmail = property("sys.admin.email").orElse("admin@example.com");
        authService.adminPassword = property("sys.admin.password").orElse("admin");
        authService.adminName = property("sys.admin.name").orElse("Admin");
        onStartup(authService::initialize);

        api().service(AuthWebService.class, bind(AuthWebServiceImpl.class));
    }
}
