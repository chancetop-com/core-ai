package ai.core.cli.a2a.handler;

import ai.core.api.a2a.A2ACapabilities;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

/**
 * @author stephen
 */
public class CapabilitiesHandler implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(JsonUtil.toJson(A2ACapabilities.cliMode()));
    }
}
