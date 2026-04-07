package ai.core.cli.a2a.handler;

import ai.core.agent.Agent;
import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.PathTemplateMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class LocalAgentHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAgentHandler.class);

    private final Supplier<Agent> agentFactory;

    public LocalAgentHandler(Supplier<Agent> agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        try {
            var match = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
            String agentId = match != null ? match.getParameters().get("id") : null;

            if (agentId != null) {
                handleGetById(exchange, agentId);
            } else {
                handleList(exchange);
            }
        } catch (Exception e) {
            LOGGER.error("failed to handle agents request", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
        }
    }

    private void handleList(HttpServerExchange exchange) {
        Agent agent = null;
        try {
            agent = agentFactory.get();
        } catch (Exception e) {
            LOGGER.debug("failed to create agent for info", e);
        }

        var agentView = createAgentView(agent, true);
        var response = new ListAgentsResponse();
        response.agents = List.of(agentView);
        response.total = 1L;
        sendJson(exchange, JsonUtil.toJson(response));
    }

    private void handleGetById(HttpServerExchange exchange, @SuppressWarnings("unused") String agentId) {
        Agent agent = null;
        try {
            agent = agentFactory.get();
        } catch (Exception e) {
            LOGGER.debug("failed to create agent for info", e);
        }

        var agentView = createAgentView(agent, true);
        sendJson(exchange, JsonUtil.toJson(agentView));
    }

    private AgentDefinitionView createAgentView(Agent agent, boolean published) {
        var view = new AgentDefinitionView();
        view.id = "local";
        view.name = "Local Agent";
        view.description = "Local CLI agent";
        view.systemPrompt = "";
        view.systemPromptId = "";
        view.temperature = 0.7;
        view.maxTurns = 30;
        view.timeoutSeconds = 300;
        view.toolIds = List.of();
        view.inputTemplate = "";
        view.variables = null;
        view.webhookSecret = "";
        view.systemDefault = true;
        view.type = "local";
        view.responseSchema = null;
        view.createdBy = "local";
        view.status = published ? "PUBLISHED" : "DRAFT";
        view.publishedAt = null;
        view.createdAt = null;
        view.updatedAt = null;

        if (agent != null) {
            // Try to extract model name from agent config
            try {
                var model = agent.getModel();
                if (model != null && !model.isEmpty()) {
                    view.model = model;
                }
            } catch (Exception e) {
                LOGGER.debug("failed to get model from agent", e);
            }
        }

        if (view.model == null || view.model.isEmpty()) {
            view.model = "unknown";
        }

        return view;
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }
}
