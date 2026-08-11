package ai.core.server;

import ai.core.server.gateway.GatewaySecretProtector;
import ai.core.server.settings.SystemSettingsService;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SettingsModule extends Module {
    @Override
    protected void initialize() {
        bindGatewaySecretProtector();
        bind(SystemSettingsService.class);
    }

    private void bindGatewaySecretProtector() {
        var secret = property("sys.gateway.secret.key").map(String::trim).filter(key -> !key.isBlank()).orElse(null);
        var legacy = requiredProperty("sys.mongo.uri");
        bind(secret == null ? new GatewaySecretProtector(legacy) : new GatewaySecretProtector(secret, legacy));
    }
}
