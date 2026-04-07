package ai.core.cli.a2a.handler;

import ai.core.api.server.tool.ListToolsResponse;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.List;

/**
 * @author stephen
 */
public class LocalToolHandler implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        var response = new ListToolsResponse();
        response.tools = List.of();
        response.total = 0L;
        sendJson(exchange, JsonUtil.toJson(response));
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }
}
