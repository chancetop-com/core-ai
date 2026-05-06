package ai.core.cli.a2a.handler;

import ai.core.a2a.A2ARunManager;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.List;

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
        var card = runManager.getAgentCard();
        if (card.supportedInterfaces == null || card.supportedInterfaces.isEmpty()) {
            card.supportedInterfaces = List.of(new ai.core.api.a2a.AgentCard.AgentInterface());
        }
        var primaryInterface = card.supportedInterfaces.getFirst();
        if (primaryInterface.url == null) {
            primaryInterface.url = exchange.getRequestScheme() + "://" + exchange.getHostAndPort();
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/a2a+json");
        exchange.getResponseSender().send(JsonUtil.toJson(card));
    }
}
