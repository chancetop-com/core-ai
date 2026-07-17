package ai.core.server;

import ai.core.server.domain.migration.SchemaMigrationManager;
import ai.core.server.web.CorsInterceptor;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.web.auth.RequestAuthenticator;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class WebFoundationModule extends Module {
    @Override
    protected void initialize() {
        var migrationManager = bind(SchemaMigrationManager.class);
        onStartup(migrationManager::migrate);

        bind(RequestAuthenticator.class);
        http().intercept(bind(AuthInterceptor.class));
        var corsInterceptor = bind(CorsInterceptor.class);
        http().intercept(corsInterceptor);
        http().errorHandler(corsInterceptor);
        site().session().timeout(Duration.ofHours(24));
        site().session().cookie("CoreAIServerSessionId", null);
    }
}
