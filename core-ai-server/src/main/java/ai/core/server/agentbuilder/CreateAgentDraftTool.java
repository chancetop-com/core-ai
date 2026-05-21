package ai.core.server.agentbuilder;

import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public final class CreateAgentDraftTool extends ToolCall {
    public static final String TOOL_NAME = "create_agent_draft";

    public static CreateAgentDraftTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new CreateAgentDraftTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;

    private CreateAgentDraftTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);

            var request = new CreateAgentRequest();
            request.name = (String) args.get("name");
            request.description = (String) args.get("description");
            request.systemPrompt = (String) args.get("system_prompt");
            request.model = (String) args.get("model");
            request.type = "AGENT";

            var temperatureObj = args.get("temperature");
            if (temperatureObj instanceof Number number) {
                request.temperature = number.doubleValue();
            }

            var maxTurnsObj = args.get("max_turns");
            if (maxTurnsObj instanceof Number number) {
                request.maxTurns = number.intValue();
            }

            var timeoutObj = args.get("timeout_seconds");
            if (timeoutObj instanceof Number number) {
                request.timeoutSeconds = number.intValue();
            }

            var toolIds = (List<String>) args.get("tool_ids");
            if (toolIds != null && !toolIds.isEmpty()) {
                var toolRefs = new ArrayList<ai.core.api.server.tool.ToolRefView>();
                for (var toolId : toolIds) {
                    var ref = new ai.core.api.server.tool.ToolRefView();
                    ref.id = toolId;
                    toolRefs.add(ref);
                }
                request.tools = toolRefs;
            }

            request.inputTemplate = (String) args.get("input_template");

            var view = agentDefinitionService.create(request, "system");

            var result = new StringBuilder(256);
            result.append("Agent draft created successfully!\n\n")
                .append("ID: ").append(view.id).append("\n")
                .append("Name: ").append(view.name).append("\n")
                .append("Status: DRAFT\n")
                .append("Type: AGENT\n\n")
                .append("You can now review the draft with the user and publish it when ready.");

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Create agent draft failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, CreateAgentDraftTool> {
        private final CreateAgentDraftTool tool;

        Builder(CreateAgentDraftTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Create a new agent definition as a draft. The agent will be in DRAFT status and can be published later with publish_agent_draft.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "name", "Name of the agent").required(),
                ToolCallParameters.ParamSpec.of(String.class, "description", "What this agent does").required(),
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt", "The system prompt that defines the agent's behavior").required(),
                ToolCallParameters.ParamSpec.of(String.class, "model", "LLM model to use (optional)"),
                ToolCallParameters.ParamSpec.of(Double.class, "temperature", "Temperature 0-1 (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "max_turns", "Maximum conversation turns (optional, default 20)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "timeout_seconds", "Timeout in seconds (optional, default 600)"),
                ToolCallParameters.ParamSpec.of(String.class, "input_template", "Input template with {{variable}} placeholders (optional)"),
                ToolCallParameters.ParamSpec.of(List.class, "tool_ids", "List of builtin tool IDs to assign to the agent (optional), e.g. [\"builtin-all\"]")
            ));
            build(tool);
        }
    }
}
