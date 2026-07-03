package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;

record GatewayRoute(GatewayProviderConfig provider, String upstreamModel) {
}
