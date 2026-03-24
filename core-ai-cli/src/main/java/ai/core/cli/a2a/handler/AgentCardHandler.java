package ai.core.cli.a2a.handler;

import ai.core.a2a.A2ARunManager;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

/**
 * @author stephen
 */
public class AgentCardHandler implements HttpHandler {
    private final A2ARunManager runManager;

    public AgentCardHandler(A2ARunManager runManager) {
        this.runManager = runManager;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(JsonUtil.toJson(runManager.getAgentCard()));
    }
}
