package ai.core.server;

import ai.core.server.domain.migration.SchemaMigrationManager;
import ai.core.server.web.CorsInterceptor;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.web.auth.PermissionInterceptor;
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
        var permissionInterceptor = bind(PermissionInterceptor.class);
        permissionInterceptor.authDisabled = "true".equals(property("sys.auth.disabled").orElse("false"));
        http().intercept(permissionInterceptor);
        var corsInterceptor = bind(CorsInterceptor.class);
        http().intercept(corsInterceptor);
        http().errorHandler(corsInterceptor);
        // core-ng gzips only text/* and application/json bodies over 200 bytes, and only when the
        // client asked: SSE (text/event-stream) and binary responses are never compressed, so
        // streaming and file downloads are unaffected. A hub catalog of a thousand tools is ~1 MB
        // of JSON and lands at a tenth of that on the wire.
        http().gzip();
        site().session().timeout(Duration.ofHours(24));
        site().session().cookie("CoreAIServerSessionId", null);
    }
}
