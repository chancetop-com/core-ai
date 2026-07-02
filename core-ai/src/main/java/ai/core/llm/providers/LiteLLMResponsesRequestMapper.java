package ai.core.llm.providers;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import core.framework.util.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LiteLLMResponsesRequestMapper {
    static boolean isResponsesModel(String model) {
        return model != null && (model.startsWith("responses/") || model.contains("/responses/"));
    }

    static Map<String, Object> toResponsesBody(CompletionRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", normalizeResponsesModel(request.model));
        body.put("input", mapInput(request.messages));
        body.put("stream", true);
        LiteLLMResponsesUtil.putIfNotNull(body, "instructions", instructions(request.messages));
        LiteLLMResponsesUtil.putIfNotNull(body, "temperature", request.temperature);
        LiteLLMResponsesUtil.putIfNotNull(body, "top_p", request.topP);
        LiteLLMResponsesUtil.putIfNotNull(body, "max_output_tokens", request.maxCompletionTokens);
        LiteLLMResponsesUtil.putIfNotNull(body, "parallel_tool_calls", request.parallelToolCalls);
        LiteLLMResponsesUtil.putIfNotNull(body, "tool_choice", request.toolChoice);
        LiteLLMResponsesUtil.putIfNotNull(body, "tools", mapTools(request.tools));
        LiteLLMResponsesUtil.putIfNotNull(body, "text", mapText(request.responseFormat));
        LiteLLMResponsesUtil.putIfNotNull(body, "reasoning", mapReasoning(request.reasoningEffort));
        return body;
    }

    private static String normalizeResponsesModel(String model) {
        return model == null ? null : model.replace("responses/", "");
    }

    private static String instructions(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        var instructions = new ArrayList<String>();
        for (var message : messages) {
            if (message.role == RoleType.SYSTEM && message.content != null) {
                var text = textContent(message.content);
                if (!Strings.isBlank(text)) instructions.add(text);
            }
        }
        return instructions.isEmpty() ? null : String.join("\n\n", instructions);
    }

    private static List<Map<String, Object>> mapInput(List<Message> messages) {
        var input = new ArrayList<Map<String, Object>>();
        if (messages == null) return input;
        for (var message : messages) {
            addMessageInput(input, message);
        }
        return input;
    }

    private static void addMessageInput(List<Map<String, Object>> input, Message message) {
        if (message.role == RoleType.SYSTEM && isTextOnly(message.content)) return;
        if (message.role == RoleType.TOOL) {
            input.add(mapFunctionCallOutput(message));
            return;
        }
        if (shouldAddMessageItem(message)) {
            input.add(mapMessageItem(message));
        }
        if (message.role == RoleType.ASSISTANT && message.toolCalls != null) {
            for (var toolCall : message.toolCalls) {
                input.add(mapFunctionCall(toolCall));
            }
        }
    }

    private static boolean shouldAddMessageItem(Message message) {
        if (message.role == RoleType.TOOL) return false;
        if (message.content == null || message.content.isEmpty()) return false;
        if (message.role != RoleType.ASSISTANT) return true;
        return message.content.stream().anyMatch(content -> content.type != Content.ContentType.TEXT || !Strings.isBlank(content.text));
    }

    private static Map<String, Object> mapMessageItem(Message message) {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "message");
        item.put("role", roleValue(message.role));
        item.put("content", mapContentParts(message.content, message.role == RoleType.ASSISTANT));
        return item;
    }

    private static Map<String, Object> mapFunctionCall(FunctionCall toolCall) {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "function_call");
        LiteLLMResponsesUtil.putIfNotNull(item, "call_id", toolCall.id);
        if (toolCall.function != null) {
            LiteLLMResponsesUtil.putIfNotNull(item, "name", toolCall.function.name);
            LiteLLMResponsesUtil.putIfNotNull(item, "arguments", toolCall.function.arguments);
        }
        return item;
    }

    private static Map<String, Object> mapFunctionCallOutput(Message message) {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "function_call_output");
        LiteLLMResponsesUtil.putIfNotNull(item, "call_id", message.toolCallId);
        item.put("output", mapContentParts(message.content, false));
        return item;
    }

    private static List<Map<String, Object>> mapContentParts(List<Content> contents, boolean outputText) {
        var parts = new ArrayList<Map<String, Object>>();
        if (contents == null || contents.isEmpty()) {
            parts.add(textPart("", outputText));
            return parts;
        }
        for (var content : contents) {
            addContentPart(parts, content, outputText);
        }
        if (parts.isEmpty()) {
            parts.add(textPart("", outputText));
        }
        return parts;
    }

    private static void addContentPart(List<Map<String, Object>> parts, Content content, boolean outputText) {
        if (content == null || content.type == null || content.type == Content.ContentType.TEXT) {
            parts.add(textPart(content == null ? "" : content.text, outputText));
            return;
        }
        if (content.type == Content.ContentType.IMAGE_URL && content.imageUrl != null) {
            parts.add(imagePart(content));
            return;
        }
        if (content.type == Content.ContentType.FILE && content.file != null) {
            parts.add(filePart(content.file));
        }
    }

    private static Map<String, Object> textPart(String text, boolean outputText) {
        var part = new LinkedHashMap<String, Object>();
        part.put("type", outputText ? "output_text" : "input_text");
        part.put("text", text == null ? "" : text);
        return part;
    }

    private static Map<String, Object> imagePart(Content content) {
        var part = new LinkedHashMap<String, Object>();
        part.put("type", "input_image");
        LiteLLMResponsesUtil.putIfNotNull(part, "image_url", content.imageUrl.url);
        LiteLLMResponsesUtil.putIfNotNull(part, "detail", content.imageUrl.detail);
        return part;
    }

    private static Map<String, Object> filePart(Content.FileContent file) {
        var part = new LinkedHashMap<String, Object>();
        part.put("type", "input_file");
        if (!Strings.isBlank(file.fileId)) {
            var idKey = file.fileId.startsWith("http://") || file.fileId.startsWith("https://") ? "file_url" : "file_id";
            part.put(idKey, file.fileId);
        }
        LiteLLMResponsesUtil.putIfNotNull(part, "filename", file.filename);
        LiteLLMResponsesUtil.putIfNotNull(part, "file_data", file.fileData);
        return part;
    }

    private static List<Map<String, Object>> mapTools(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) return null;
        var mapped = new ArrayList<Map<String, Object>>();
        for (var tool : tools) {
            if (tool.type == ToolType.FUNCTION && tool.function != null) {
                mapped.add(mapFunctionTool(tool));
            }
        }
        return mapped.isEmpty() ? null : mapped;
    }

    private static Map<String, Object> mapFunctionTool(Tool tool) {
        var function = tool.function;
        var mappedTool = new LinkedHashMap<String, Object>();
        mappedTool.put("type", "function");
        mappedTool.put("name", function.name);
        LiteLLMResponsesUtil.putIfNotNull(mappedTool, "description", function.description);
        LiteLLMResponsesUtil.putIfNotNull(mappedTool, "parameters", function.parameters);
        LiteLLMResponsesUtil.putIfNotNull(mappedTool, "strict", function.strict);
        return mappedTool;
    }

    private static Map<String, Object> mapText(ResponseFormat responseFormat) {
        if (responseFormat == null) return null;
        var format = new LinkedHashMap<String, Object>();
        if ("json_object".equals(responseFormat.type)) {
            format.put("type", "json_object");
        } else if ("text".equals(responseFormat.type)) {
            format.put("type", "text");
        } else {
            addJsonSchemaFormat(format, responseFormat);
        }
        return LiteLLMResponsesUtil.mapOf("format", format);
    }

    private static void addJsonSchemaFormat(Map<String, Object> format, ResponseFormat responseFormat) {
        format.put("type", "json_schema");
        if (responseFormat.jsonSchema == null) return;
        LiteLLMResponsesUtil.putIfNotNull(format, "name", responseFormat.jsonSchema.name);
        LiteLLMResponsesUtil.putIfNotNull(format, "strict", responseFormat.jsonSchema.strict);
        LiteLLMResponsesUtil.putIfNotNull(format, "schema", responseFormat.jsonSchema.schema);
    }

    private static Map<String, Object> mapReasoning(ReasoningEffort reasoningEffort) {
        if (reasoningEffort == null) return null;
        return LiteLLMResponsesUtil.mapOf("effort", reasoningEffortValue(reasoningEffort));
    }

    private static String reasoningEffortValue(ReasoningEffort effort) {
        return switch (effort) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case MAX -> "max";
        };
    }

    private static String roleValue(RoleType role) {
        if (role == null) return null;
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    private static boolean isTextOnly(List<Content> contents) {
        return contents != null && contents.stream().allMatch(content -> content == null || content.type == Content.ContentType.TEXT);
    }

    private static String textContent(List<Content> contents) {
        if (contents == null || contents.isEmpty()) return null;
        var text = new StringBuilder();
        for (var content : contents) {
            if (content == null || content.type == Content.ContentType.TEXT) {
                if (!text.isEmpty()) text.append('\n');
                text.append(content == null || content.text == null ? "" : content.text);
            }
        }
        return text.toString();
    }

    private LiteLLMResponsesRequestMapper() {
    }
}
