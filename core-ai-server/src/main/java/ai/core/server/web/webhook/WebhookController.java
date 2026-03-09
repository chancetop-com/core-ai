package ai.core.server.web.webhook;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.TriggerType;
import ai.core.server.run.AgentRunner;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @author stephen
 */
public class WebhookController implements Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    AgentRunner agentRunner;

    @Override
    public Response execute(Request request) {
        var agentId = request.pathParam("agentId");
        var definition = agentDefinitionCollection.get(agentId)
                .orElseThrow(() -> new NotFoundException("agent not found, id=" + agentId));

        validateSecret(request, definition);

        if (definition.publishedConfig == null) {
            throw new NotFoundException("agent not published, id=" + agentId);
        }

        var input = buildInput(request, definition);
        var runId = agentRunner.run(definition, input, TriggerType.WEBHOOK);

        LOGGER.info("webhook triggered agent run, agentId={}, runId={}", agentId, runId);
        var result = new WebhookTriggerResponse();
        result.runId = runId;
        result.status = "RUNNING";
        return Response.bean(result);
    }

    private String buildInput(Request request, AgentDefinition definition) {
        var body = request.body();
        if (body.isEmpty()) {
            return definition.publishedConfig.inputTemplate;
        }
        var bodyStr = new String(body.get(), StandardCharsets.UTF_8);
        if (bodyStr.isBlank()) {
            return definition.publishedConfig.inputTemplate;
        }

        var template = definition.publishedConfig.inputTemplate;
        if (template == null) {
            return bodyStr;
        }

        if (template.contains("{{payload}}")) {
            return template.replace("{{payload}}", bodyStr);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) JSON.fromJSON(Map.class, bodyStr);
            var result = template;
            for (var entry : payload.entrySet()) {
                var placeholder = "{{" + entry.getKey() + "}}";
                if (result.contains(placeholder) && entry.getValue() != null) {
                    result = result.replace(placeholder, String.valueOf(entry.getValue()));
                }
            }
            return result;
        } catch (Exception e) {
            return bodyStr;
        }
    }

    private void validateSecret(Request request, AgentDefinition definition) {
        if (definition.webhookSecret == null || definition.webhookSecret.isBlank()) {
            throw new ForbiddenException("webhook not enabled for this agent");
        }
        var auth = request.header("Authorization");
        if (auth.isEmpty() || !auth.get().startsWith("Bearer ")) {
            throw new ForbiddenException("missing or invalid authorization header");
        }
        var token = auth.get().substring(7);
        if (!definition.webhookSecret.equals(token)) {
            throw new ForbiddenException("invalid webhook secret");
        }
    }
}
