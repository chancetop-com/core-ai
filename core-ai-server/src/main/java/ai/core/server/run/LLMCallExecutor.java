package ai.core.server.run;

import ai.core.agent.ExecutionContext;
import ai.core.agent.internal.AgentHelper;
import ai.core.api.jsonschema.JsonSchema;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class LLMCallExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(LLMCallExecutor.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int EXTRACTION_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH_FOR_EXTRACTION = 16000;

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
        var multiModalModel = resolveMultiModalModel(config, definition.multiModalModel);
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

        var effectiveModel = hasAttachments(attachments) && multiModalModel != null ? multiModalModel : model;
        var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
            messages, null, temperature, effectiveModel, null, false, responseFormat, null
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

    private String resolveMultiModalModel(AgentPublishedConfig config, String fallback) {
        return config != null ? config.multiModalModel : fallback;
    }

    private boolean hasAttachments(List<LLMCallRequest.Attachment> attachments) {
        return attachments != null && !attachments.isEmpty();
    }

    private Double resolveTemperature(AgentPublishedConfig config, Double fallback) {
        return config != null ? config.temperature : fallback;
    }

    private int resolveTimeout(AgentPublishedConfig config, AgentDefinition definition) {
        if (config != null && config.timeoutSeconds != null) return config.timeoutSeconds;
        if (definition.timeoutSeconds != null) return definition.timeoutSeconds;
        return DEFAULT_TIMEOUT_SECONDS;
    }

    public Map<String, Object> extractStructured(String output, Dataset dataset, AgentDefinition definition) {
        if (output == null || output.isBlank()) return Map.of();

        // When no schema defined, save raw output as-is without any LLM call
        if (dataset.schema == null || dataset.schema.isEmpty()) {
            var result = new LinkedHashMap<String, Object>();
            result.put("output", output);
            return result;
        }

        var config = definition.publishedConfig;
        var responseSchemaJson = config != null ? config.responseSchema : definition.responseSchema;

        if (responseSchemaJson != null) {
            return extractStrategyA(output, dataset);
        }
        return extractStrategyB(output, dataset, definition);
    }

    private Map<String, Object> extractStrategyA(String output, Dataset dataset) {
        try {
            Map<String, Object> parsed = JsonUtil.toMap(output);
            if (parsed == null || parsed.isEmpty()) return Map.of();

            var result = new LinkedHashMap<String, Object>();
            for (var field : dataset.schema) {
                if (parsed.containsKey(field.name)) {
                    result.put(field.name, parsed.get(field.name));
                } else {
                    result.put(field.name, null);
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("strategy A extraction failed, falling back to strategy B, error={}", e.getMessage());
            return extractStrategyB(output, dataset, null);
        }
    }

    private Map<String, Object> extractStrategyB(String output, Dataset dataset, AgentDefinition definition) {
        if (dataset.schema == null || dataset.schema.isEmpty()) return Map.of();

        var truncated = output.length() > MAX_OUTPUT_LENGTH_FOR_EXTRACTION
            ? output.substring(0, MAX_OUTPUT_LENGTH_FOR_EXTRACTION) + "...(truncated)"
            : output;

        var prompt = buildExtractionPrompt(dataset.schema, truncated);
        var schema = buildDatasetJsonSchema(dataset.schema);

        var responseFormat = new ResponseFormat();
        var schemaDef = new ResponseFormat.JsonSchemaDefinition();
        schemaDef.name = "dataset_extraction";
        schemaDef.strict = false;
        schemaDef.schema = schema;
        responseFormat.jsonSchema = schemaDef;

        var model = definition != null ? definition.model : null;
        if (definition != null && definition.publishedConfig != null && definition.publishedConfig.model != null) {
            model = definition.publishedConfig.model;
        }

        var messages = new ArrayList<Message>();
        messages.add(Message.of(RoleType.USER, prompt));

        var provider = llmProviders.getProvider();
        var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
            messages, null, 0.0, model, null, false, responseFormat, null
        ));
        request.setTimeoutSeconds(EXTRACTION_TIMEOUT_SECONDS);

        var response = provider.completion(request);
        var content = response.choices.getFirst().message.content;

        try {
            return JsonUtil.toMap(content);
        } catch (Exception e) {
            LOGGER.error("failed to parse extraction LLM response, content={}", content, e);
            return Map.of();
        }
    }

    private String buildExtractionPrompt(List<SchemaField> schema, String output) {
        var sb = new StringBuilder();
        sb.append("Extract the following structured data from the agent's output below.\n\n");
        sb.append("Schema fields:\n");
        for (var field : schema) {
            sb.append("- ").append(field.name).append(" (").append(field.type.name().toLowerCase()).append(")");
            if (field.label != null) sb.append(": ").append(field.label);
            sb.append("\n");
        }
        sb.append("\nRules:\n");
        sb.append("- Extract only values explicitly stated in the text. Do not infer or fabricate data.\n");
        sb.append("- If a field is not present in the text, set it to null.\n");
        sb.append("- For numeric fields, coerce string representations to numbers (e.g., \"42\" → 42).\n");
        sb.append("- For boolean fields, accept true/false or yes/no.\n");
        sb.append("- Return a valid JSON object matching the schema. Do not include extra fields.\n\n");
        sb.append("Agent output:\n---\n");
        sb.append(output);
        sb.append("\n---\n\nReturn JSON:");
        return sb.toString();
    }

    private JsonSchema buildDatasetJsonSchema(List<SchemaField> fields) {
        var schema = new JsonSchema();
        schema.type = JsonSchema.PropertyType.OBJECT;
        schema.additionalProperties = false;

        var properties = new LinkedHashMap<String, JsonSchema>();
        var required = new ArrayList<String>();

        for (var field : fields) {
            var prop = new JsonSchema();
            prop.type = toJsonSchemaType(field.type);
            if (field.label != null) prop.description = field.label;
            properties.put(field.name, prop);
            required.add(field.name);
        }

        schema.properties = properties;
        schema.required = required;
        return schema;
    }

    private JsonSchema.PropertyType toJsonSchemaType(SchemaFieldType type) {
        return switch (type) {
            case NUMBER -> JsonSchema.PropertyType.NUMBER;
            case STRING -> JsonSchema.PropertyType.STRING;
            case BOOLEAN -> JsonSchema.PropertyType.BOOLEAN;
        };
    }

    public record Result(String output, long inputTokens, long outputTokens) { }
}
