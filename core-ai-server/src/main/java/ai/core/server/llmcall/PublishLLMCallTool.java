package ai.core.server.llmcall;

import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public final class PublishLLMCallTool extends ToolCall {
    public static final String TOOL_NAME = "publish_llm_call";

    public static PublishLLMCallTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new PublishLLMCallTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;
    private String userId;

    private PublishLLMCallTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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
            request.inputTemplate = (String) args.get("input_template");
            request.model = (String) args.get("model");
            request.type = "LLM_CALL";

            var temperatureObj = args.get("temperature");
            if (temperatureObj instanceof Number number) {
                request.temperature = number.doubleValue();
            }

            var schemaJson = (String) args.get("response_schema_json");
            if (schemaJson != null && !schemaJson.isBlank()) {
                List<ApiDefinitionType> schemaTypes = JsonUtil.fromJson(new TypeReference<>() { }, schemaJson);
                request.responseSchema = schemaTypes;
            }

            var view = agentDefinitionService.create(request, userId != null ? userId : "system");
            agentDefinitionService.publish(view.id);

            var result = new StringBuilder(256);
            result.append("LLM Call API published successfully!\n\nID: ")
                .append(view.id)
                .append("\nName: ")
                .append(view.name)
                .append("\nTrigger endpoint: POST /api/runs/agent/")
                .append(view.id)
                .append("/trigger\nRequest body: {\"input\": \"your input text\"}\n");

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Publish failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, PublishLLMCallTool> {
        private final PublishLLMCallTool tool;

        Builder(PublishLLMCallTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Create and publish an LLM Call API definition. Returns the definition ID and trigger endpoint.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "name", "Name of the LLM Call API").required(),
                ToolCallParameters.ParamSpec.of(String.class, "description", "What this LLM Call does").required(),
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt", "The system prompt").required(),
                ToolCallParameters.ParamSpec.of(String.class, "response_schema_json", "The response schema as JSON array of ApiDefinitionType (optional, omit for plain text output)"),
                ToolCallParameters.ParamSpec.of(String.class, "input_template", "Input template with {{variable}} placeholders (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "model", "LLM model (optional)"),
                ToolCallParameters.ParamSpec.of(Double.class, "temperature", "Temperature 0-1 (optional)")
            ));
            build(tool);
        }
    }
}
