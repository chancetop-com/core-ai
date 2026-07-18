package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import core.framework.http.HTTPRequest;

record GatewayUpstreamCall(HTTPRequest request, GatewayProviderConfig provider, String requestedModel,
                           String upstreamModel, boolean stream) {
}
