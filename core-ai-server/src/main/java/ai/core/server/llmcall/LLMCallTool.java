package ai.core.server.llmcall;

import ai.core.api.server.run.LLMCallRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;

/**
 * Wraps an LLM_CALL agent definition as a callable tool for agents.
 * <p>
 * Each execution delegates to {@link LLMCallExecutor} with the definition's
 * system prompt / model / response schema, and returns the structured output
 * as the tool result. The definition is snapshotted at resolution time, so
 * publish/draft changes only take effect on the next resolution (session rebuild).
 *
 * @author stephen
 */
public final class LLMCallTool extends ToolCall {
    public static LLMCallTool create(AgentDefinition definition, LLMCallExecutor executor) {
        if (definition.type != DefinitionType.LLM_CALL) {
            throw new IllegalArgumentException("definition is not LLM_CALL type, id=" + definition.id + ", type=" + definition.type);
        }
        var tool = new LLMCallTool(definition, executor);
        new Builder(tool, definition).build();
        return tool;
    }

    private final AgentDefinition definition;
    private final LLMCallExecutor executor;

    private LLMCallTool(AgentDefinition definition, LLMCallExecutor executor) {
        this.definition = definition;
        this.executor = executor;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        try {
            var args = parseArguments(arguments);
            var query = getStringValue(args, "query");
            if (query == null || query.isBlank()) {
                return ToolCallResult.failed("Parameter 'query' is required for llm call tool " + getName());
            }
            var imageUrl = getStringValue(args, "image_url");
            var attachments = imageUrl != null ? List.of(toImageAttachment(imageUrl)) : null;
            var result = executor.execute(definition, buildInput(query), attachments);
            return ToolCallResult.completed(result.output())
                    .withToolName(getName())
                    .withStats("llm_call_input_tokens", result.inputTokens())
                    .withStats("llm_call_output_tokens", result.outputTokens());
        } catch (Exception e) {
            return ToolCallResult.failed("LLM call tool '" + getName() + "' execution error: " + e.getMessage(), e)
                    .withToolName(getName());
        }
    }

    @Override
    public long getTimeoutMs() {
        var config = definition.publishedConfig;
        var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds : definition.timeoutSeconds;
        return timeoutSeconds != null ? timeoutSeconds * 1000L : DEFAULT_TIMEOUT_MS;
    }

    private String buildInput(String query) {
        var config = definition.publishedConfig;
        var template = config != null && config.inputTemplate != null ? config.inputTemplate : definition.inputTemplate;
        if (template == null || template.isBlank()) return query;
        if (template.contains("{{query}}")) return template.replace("{{query}}", query);
        return template + "\n" + query;
    }

    private LLMCallRequest.Attachment toImageAttachment(String imageUrl) {
        var attachment = new LLMCallRequest.Attachment();
        attachment.url = imageUrl;
        attachment.type = LLMCallRequest.AttachmentType.IMAGE;
        return attachment;
    }

    private static class Builder extends ToolCall.Builder<Builder, LLMCallTool> {
        private final LLMCallTool tool;
        private final AgentDefinition definition;

        Builder(LLMCallTool tool, AgentDefinition definition) {
            this.tool = tool;
            this.definition = definition;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(sanitizeName(definition.name));
            description(definition.description != null && !definition.description.isBlank()
                    ? definition.description
                    : "Call the LLM definition " + definition.name + " and return its structured output.");
            sourceType("llm-call");
            parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "query", "The input to send to the LLM call definition.").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "image_url", "Optional image URL to include as an attachment; routes to the definition's multi-modal model.")
            ));
            build(tool);
        }

        private String sanitizeName(String name) {
            return name == null ? "llm-call" : name.trim().replaceAll("[\\s<|\\\\/>]+", "-");
        }
    }
}
