package ai.core.server.run;

import ai.core.agent.ExecutionContext;
import ai.core.agent.internal.AgentHelper;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.systemprompt.SystemPromptService;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class LLMCallExecutor {
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;

    @Inject
    LLMProviders llmProviders;

    @Inject
    SystemPromptService systemPromptService;

    public Result execute(AgentDefinition definition, String input) {
        return execute(definition, input, null);
    }

    public Result execute(AgentDefinition definition, String input, List<LLMCallRequest.Attachment> attachments) {
        var config = definition.publishedConfig;
        var systemPrompt = resolveSystemPrompt(config, definition);
        var model = resolveModel(config, definition.model);
        var temperature = resolveTemperature(config, definition.temperature);
        var timeoutSeconds = resolveTimeout(config, definition);
        var responseSchemaJson = config != null ? config.responseSchema : definition.responseSchema;

        ResponseFormat responseFormat = null;
        if (responseSchemaJson != null) {
            responseFormat = ResponseSchemaConverter.fromJsonSchema(responseSchemaJson);
        }

        var messages = new ArrayList<Message>();
        if (systemPrompt != null) {
            messages.add(Message.of(RoleType.SYSTEM, systemPrompt));
        }
        messages.add(buildUserMessage(input, attachments));

        var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
            messages, null, temperature, model, null, false, responseFormat, null
        ));
        request.setTimeoutSeconds(timeoutSeconds);

        var provider = llmProviders.getProvider();
        var response = provider.completion(request);

        var output = response.choices.getFirst().message.content;
        long inputTokens = 0;
        long outputTokens = 0;
        if (response.usage != null) {
            inputTokens = response.usage.getPromptTokens();
            outputTokens = response.usage.getCompletionTokens();
        }
        return new Result(output, inputTokens, outputTokens);
    }

    private Message buildUserMessage(String input, List<LLMCallRequest.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Message.of(RoleType.USER, input);
        }
        var attachment = attachments.getFirst();
        var type = ExecutionContext.AttachedContent.AttachedContentType.valueOf(attachment.type.name());
        ExecutionContext.AttachedContent attachedContent;
        if (attachment.data != null) {
            attachedContent = ExecutionContext.AttachedContent.ofBase64(attachment.data, attachment.mediaType, type);
        } else {
            attachedContent = ExecutionContext.AttachedContent.ofUrl(attachment.url, type);
        }
        return AgentHelper.buildUserMessage(input, attachedContent);
    }

    private String resolveSystemPrompt(AgentPublishedConfig config, AgentDefinition definition) {
        var promptId = config != null ? config.systemPromptId : definition.systemPromptId;
        if (promptId != null && !promptId.isBlank()) {
            return systemPromptService.resolveContent(promptId);
        }
        return config != null ? config.systemPrompt : definition.systemPrompt;
    }

    private String resolveModel(AgentPublishedConfig config, String fallback) {
        return config != null ? config.model : fallback;
    }

    private Double resolveTemperature(AgentPublishedConfig config, Double fallback) {
        return config != null ? config.temperature : fallback;
    }

    private int resolveTimeout(AgentPublishedConfig config, AgentDefinition definition) {
        if (config != null && config.timeoutSeconds != null) return config.timeoutSeconds;
        if (definition.timeoutSeconds != null) return definition.timeoutSeconds;
        return DEFAULT_TIMEOUT_SECONDS;
    }

    public record Result(String output, long inputTokens, long outputTokens) { }
}
