package ai.core.server;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.gateway.GatewaySecretProtector;
import ai.core.server.rbac.RoleRegistry;
import ai.core.server.settings.MongoSystemSettingsStore;
import ai.core.server.settings.PgSystemSettingsStore;
import ai.core.server.settings.SandboxSnapshotSettingsService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.settings.SystemSettingsStore;
import ai.core.server.settings.TraceArchiveSettings;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SettingsModule extends Module {
    @Override
    protected void initialize() {
        bindGatewaySecretProtector();
        bindSystemSettingsStore();
        bind(TraceArchiveSettings.class);
        bind(SystemSettingsService.class);
        bind(RoleRegistry.class);
        bind(PermissionService.class);
        bind(SandboxSnapshotSettingsService.class);
    }

    private void bindSystemSettingsStore() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SystemSettingsStore.class, bind(PgSystemSettingsStore.class));
        } else {
            bind(SystemSettingsStore.class, bind(MongoSystemSettingsStore.class));
        }
    }

    private void bindGatewaySecretProtector() {
        var secret = property("sys.gateway.secret.key").map(String::trim).filter(key -> !key.isBlank()).orElse(null);
        var legacy = requiredProperty("sys.mongo.uri");
        bind(secret == null ? new GatewaySecretProtector(legacy) : new GatewaySecretProtector(secret, legacy));
    }
}
