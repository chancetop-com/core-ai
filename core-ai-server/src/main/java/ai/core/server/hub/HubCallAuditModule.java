package ai.core.server.hub;

import core.framework.module.Module;

/**
 * Binds the shared {@code hub_calls} audit writer early enough for every surface that records into it,
 * including the model gateway controllers loaded by {@link ai.core.server.GatewayModule}.
 *
 * @author stephen
 */
public class HubCallAuditModule extends Module {
    @Override
    protected void initialize() {
        bind(HubCallAuditService.class);
    }
}
