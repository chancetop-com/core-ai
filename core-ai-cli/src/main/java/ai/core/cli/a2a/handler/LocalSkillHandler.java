package ai.core.cli.a2a.handler;

import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.List;

/**
 * @author stephen
 */
public class LocalSkillHandler implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        var response = new ListSkillsResponse(List.of(), 0);
        sendJson(exchange, JsonUtil.toJson(response));
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }

    public static class ListSkillsResponse {
        public List<Object> skills;
        public int total;

        public ListSkillsResponse(List<Object> skills, int total) {
            this.skills = skills;
            this.total = total;
        }
    }
}
