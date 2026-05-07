package ai.core.server.trigger.action;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.TriggerType;
import ai.core.server.run.AgentRunner;
import ai.core.server.trigger.domain.Trigger;
import ai.core.server.trigger.filter.EventFilter;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class RunAgentAction implements TriggerAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunAgentAction.class);

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    AgentRunner agentRunner;

    @Override
    public String type() {
        return "RUN_AGENT";
    }

    @Override
    public TriggerActionResult execute(Trigger trigger, String payload) {
        var agentId = trigger.actionConfig != null ? trigger.actionConfig.get("agent_id") : null;
        if (agentId == null || agentId.isBlank()) {
            LOGGER.warn("trigger {} has no agent_id configured, skipping", trigger.id);
            return TriggerActionResult.skipped("no agent_id configured");
        }

        var definition = agentDefinitionCollection.get(agentId)
                .orElse(null);
        if (definition == null) {
            LOGGER.warn("agent not found for trigger {}, agentId={}", trigger.id, agentId);
            return TriggerActionResult.skipped("agent not found: " + agentId);
        }

        // Apply event filter before running agent to avoid wasting tokens
        var filter = new EventFilter(trigger.actionConfig);
        if (!filter.matches(payload)) {
            LOGGER.info("trigger {} skipped by event filter for agentId={}", trigger.id, agentId);
            return TriggerActionResult.skipped("filtered by event filter");
        }

        var inputTemplate = trigger.actionConfig != null ? trigger.actionConfig.get("input_template") : null;
        if (inputTemplate == null || inputTemplate.isBlank()) {
            // Fall back to agent's inputTemplate when trigger has no explicit input_template
            // Priority: publishedConfig.inputTemplate > draft inputTemplate
            if (definition.publishedConfig != null && definition.publishedConfig.inputTemplate != null && !definition.publishedConfig.inputTemplate.isBlank()) {
                inputTemplate = definition.publishedConfig.inputTemplate;
            } else {
                inputTemplate = definition.inputTemplate;
            }
        }
        var input = resolveInput(inputTemplate, payload);

        Map<String, String> runtimeVariables = null;
        if (trigger.actionConfig != null && trigger.actionConfig.containsKey("variables")) {
            runtimeVariables = new HashMap<>();
        }

        var runId = agentRunner.run(definition, input, TriggerType.WEBHOOK, runtimeVariables);
        LOGGER.info("trigger {} triggered agent run, agentId={}, runId={}", trigger.id, agentId, runId);
        return TriggerActionResult.running(runId);
    }

    private String resolveInput(String template, String payload) {
        if (template == null || template.isBlank()) {
            return payload;
        }
        if (template.contains("{{payload}}")) {
            return template.replace("{{payload}}", payload != null ? payload : "");
        }
        return template;
    }
}
