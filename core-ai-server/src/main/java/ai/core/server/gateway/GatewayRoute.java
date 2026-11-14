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

    /**
     * Stable audit identity of the resolved route. It deliberately contains no credential and no endpoint:
     * a receipt must prove which model answered, not leak provider internals. The registered gateway model id
     * is included so a failover row of the same upstream name is distinguishable.
     */
    public String fingerprint(String requestedModel) {
        var providerName = provider == null ? null : provider.name;
        var providerType = provider == null ? null : provider.type;
        var registeredModel = model == null ? null : model.modelId;
        return "requested=" + nullToEmpty(requestedModel)
            + "/provider=" + nullToEmpty(provider == null ? null : provider.id)
            + "/providerType=" + nullToEmpty(providerType)
            + "/providerName=" + nullToEmpty(providerName)
            + "/model=" + nullToEmpty(registeredModel)
            + "/upstream=" + nullToEmpty(upstreamModel);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

