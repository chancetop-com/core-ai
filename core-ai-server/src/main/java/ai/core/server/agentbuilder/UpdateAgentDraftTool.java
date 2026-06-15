package ai.core.server.agentbuilder;

import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.DefinitionType;
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
public final class UpdateAgentDraftTool extends ToolCall {
    public static final String TOOL_NAME = "update_agent_draft";

    public static UpdateAgentDraftTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new UpdateAgentDraftTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;

    private UpdateAgentDraftTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    @Override
    @SuppressWarnings({"unchecked", "checkstyle:MethodLength", "PMD.ConsecutiveLiteralAppends", "PMD.AppendCharacterWithChar"})
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);
            var agentId = (String) args.get("agent_id");

            if (agentId == null || agentId.isBlank()) {
                return ToolCallResult.failed("agent_id is required", null)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var entity = agentDefinitionService.getEntity(agentId);
            if (entity.type != DefinitionType.AGENT) {
                return ToolCallResult.failed("This tool only updates AGENT type agents. For LLM_CALL type, use the LLM Call Builder.", null)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var request = new UpdateAgentRequest();
            request.name = (String) args.get("name");
            request.description = (String) args.get("description");
            request.systemPrompt = (String) args.get("system_prompt");
            request.model = (String) args.get("model");

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
                var toolRefs = new ArrayList<ToolRefView>();
                for (var toolId : toolIds) {
                    var ref = new ToolRefView();
                    ref.id = toolId;
                    toolRefs.add(ref);
                }
                request.tools = toolRefs;
            }

            request.inputTemplate = (String) args.get("input_template");
            request.multiModalModel = (String) args.get("multi_modal_model");

            var view = agentDefinitionService.update(agentId, request);

            var result = new StringBuilder(256);
            result.append("Agent draft updated successfully!\n\n")
                .append("ID: ").append(view.id).append("\n")
                .append("Name: ").append(view.name).append("\n")
                .append("Status: ").append(view.status).append("\n\n")
                .append("Fields updated:\n");
            if (request.name != null) result.append("  - name\n");
            if (request.description != null) result.append("  - description\n");
            if (request.systemPrompt != null) result.append("  - system_prompt\n");
            if (request.model != null) result.append("  - model\n");
            if (request.temperature != null) result.append("  - temperature\n");
            if (request.maxTurns != null) result.append("  - max_turns\n");
            if (request.timeoutSeconds != null) result.append("  - timeout_seconds\n");
            if (request.tools != null) result.append("  - tools\n");
            if (request.inputTemplate != null) result.append("  - input_template\n");
            if (request.multiModalModel != null) result.append("  - multi_modal_model\n");

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Update agent draft failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, UpdateAgentDraftTool> {
        private final UpdateAgentDraftTool tool;

        Builder(UpdateAgentDraftTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Update an existing draft agent. Only the fields you provide will be changed; others remain unchanged. Use this instead of creating a new draft when you need to modify an existing one.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "agent_id", "The ID of the draft agent to update").required(),
                ToolCallParameters.ParamSpec.of(String.class, "name", "New name for the agent (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "description", "New description (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt", "New system prompt (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "model", "New LLM model (optional)"),
                ToolCallParameters.ParamSpec.of(Double.class, "temperature", "New temperature 0-1 (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "max_turns", "New maximum conversation turns (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "timeout_seconds", "New timeout in seconds (optional)"),
                ToolCallParameters.ParamSpec.of(List.class, "tool_ids", "New list of builtin tool IDs (optional, replaces existing)"),
                ToolCallParameters.ParamSpec.of(String.class, "input_template", "New input template (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "multi_modal_model", "New multimodal model (optional, set to empty string to clear)")
            ));
            build(tool);
        }
    }
}
