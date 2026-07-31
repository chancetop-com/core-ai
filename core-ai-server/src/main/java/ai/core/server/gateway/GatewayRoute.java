package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;

public record GatewayRoute(GatewayProviderConfig provider, String upstreamModel) {
}
