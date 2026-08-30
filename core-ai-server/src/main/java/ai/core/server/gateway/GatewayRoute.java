package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;

/**
 * @param model the registered gateway model row, null for legacy prefix-based routes. Carried because
 *              reference compilation needs per-model capability facts that only the registry has.
 */
public record GatewayRoute(GatewayProviderConfig provider, String upstreamModel, GatewayModelConfig model) {
    public GatewayRoute(GatewayProviderConfig provider, String upstreamModel) {
        this(provider, upstreamModel, null);
    }
}
