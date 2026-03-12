package ai.core.server.llmcall;

import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.server.run.ResponseSchemaConverter;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public final class TestLLMCallTool extends ToolCall {
    public static final String TOOL_NAME = "test_llm_call";

    public static TestLLMCallTool create(LLMProviders llmProviders) {
        var tool = new TestLLMCallTool(llmProviders);
        new Builder(tool).build();
        return tool;
    }

    private final LLMProviders llmProviders;

    private TestLLMCallTool(LLMProviders llmProviders) {
        this.llmProviders = llmProviders;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);
            var systemPrompt = (String) args.get("system_prompt");
            var schemaJson = (String) args.get("response_schema_json");
            var testInput = (String) args.get("test_input");
            var model = (String) args.get("model");

            ResponseFormat responseFormat = null;
            if (schemaJson != null && !schemaJson.isBlank()) {
                List<ApiDefinitionType> schemaTypes = JsonUtil.fromJson(new TypeReference<>() { }, schemaJson);
                responseFormat = ResponseSchemaConverter.toResponseFormat(schemaTypes);
            }

            var messages = new ArrayList<Message>();
            messages.add(Message.of(RoleType.SYSTEM, systemPrompt));
            messages.add(Message.of(RoleType.USER, testInput));

            var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
                messages, null, null, model, null, false, responseFormat, null
            ));

            var provider = llmProviders.getProvider();
            var response = provider.completion(request);
            var output = response.choices.getFirst().message.content;

            var result = new StringBuilder(256);
            result.append("Test succeeded. LLM output:\n\n")
                .append(output);
            if (response.usage != null) {
                result.append("\n\nToken usage: input=").append(response.usage.getPromptTokens())
                    .append(", output=").append(response.usage.getCompletionTokens());
            }

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Test failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, TestLLMCallTool> {
        private final TestLLMCallTool tool;

        Builder(TestLLMCallTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Test an LLM Call definition with sample input. Sends the system prompt and test input to the LLM with the response schema, returns the structured output.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt", "The system prompt for the LLM Call").required(),
                ToolCallParameters.ParamSpec.of(String.class, "response_schema_json", "The response schema as JSON array of ApiDefinitionType (optional, omit for plain text output)"),
                ToolCallParameters.ParamSpec.of(String.class, "test_input", "Sample user input to test with").required(),
                ToolCallParameters.ParamSpec.of(String.class, "model", "LLM model to use (optional)")
            ));
            build(tool);
        }
    }
}
