package ai.core.llm.responses;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.llm.domain.responses.ResponsesTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ResponsesRequestMapper {
    public CompletionRequest map(ResponsesRequest request) {
        validateRequest(request);

        var completionRequest = new CompletionRequest();
        completionRequest.model = requireText(request.model, "model is required");
        completionRequest.messages = mapMessages(request);
        completionRequest.temperature = request.temperature;
        completionRequest.topP = request.topP;
        completionRequest.maxCompletionTokens = request.maxOutputTokens;
        completionRequest.parallelToolCalls = request.parallelToolCalls;
        completionRequest.tools = mapTools(request.tools);
        completionRequest.toolChoice = mapToolChoice(request.toolChoice);
        completionRequest.responseFormat = mapResponseFormat(request.text);
        if (request.reasoning != null) {
            completionRequest.reasoningEffort = request.reasoning.effort;
        }
        return completionRequest;
    }

    private void validateRequest(ResponsesRequest request) {
        if (request == null) throw new ResponsesValidationException("request body is required");
        if (!Boolean.TRUE.equals(request.stream)) {
            throw new ResponsesValidationException("stream=true is required for /responses bridge");
        }
        if (!isBlank(request.previousResponseId)) {
            throw new ResponsesValidationException("previous_response_id is not supported by the stateless bridge");
        }
        if (request.conversation != null) {
            throw new ResponsesValidationException("conversation is not supported by the stateless bridge");
        }
        if (Boolean.TRUE.equals(request.background)) {
            throw new ResponsesValidationException("background responses are not supported by the bridge");
        }
        if (request.prompt != null) {
            throw new ResponsesValidationException("prompt templates are not supported by the bridge");
        }
        if ("auto".equalsIgnoreCase(request.truncation)) {
            throw new ResponsesValidationException("truncation=auto is not supported by the bridge");
        }
        if (request.maxToolCalls != null) {
            throw new ResponsesValidationException("max_tool_calls is not supported by the bridge");
        }
    }

    private List<Message> mapMessages(ResponsesRequest request) {
        if (request.input == null) throw new ResponsesValidationException("input is required");

        var messages = new ArrayList<Message>();
        if (!isBlank(request.instructions)) {
            messages.add(Message.of(RoleType.SYSTEM, request.instructions));
        }

        if (request.input instanceof String text) {
            messages.add(Message.of(RoleType.USER, text));
        } else if (request.input instanceof List<?> items) {
            mapInputItems(items, messages);
        } else {
            throw new ResponsesValidationException("input must be a string or an array");
        }

        if (messages.isEmpty() || messages.size() == 1 && messages.getFirst().role == RoleType.SYSTEM) {
            throw new ResponsesValidationException("input must contain at least one user or assistant item");
        }
        return messages;
    }

    private void mapInputItems(List<?> items, List<Message> messages) {
        var pendingToolCalls = new ArrayList<FunctionCall>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawItem)) {
                throw new ResponsesValidationException("input array items must be objects");
            }
            var itemMap = asStringMap(rawItem);
            var type = stringValue(itemMap.get("type"));
            if ("reasoning".equals(type)) {
                continue;
            }
            if ("item_reference".equals(type)) {
                throw new ResponsesValidationException("item_reference input items require response storage");
            }
            if ("function_call".equals(type)) {
                pendingToolCalls.add(mapFunctionCall(itemMap, pendingToolCalls.size()));
                continue;
            }

            flushToolCalls(messages, pendingToolCalls);
            if ("function_call_output".equals(type)) {
                messages.add(mapFunctionCallOutput(itemMap));
            } else if ("message".equals(type) || itemMap.containsKey("role")) {
                messages.add(mapMessageItem(itemMap));
            } else if (isInputContentPart(type)) {
                messages.add(messageFromContentPart(RoleType.USER, itemMap));
            } else {
                throw new ResponsesValidationException("unsupported input item type: " + type);
            }
        }
        flushToolCalls(messages, pendingToolCalls);
    }

    private Message mapMessageItem(Map<String, Object> item) {
        var role = mapRole(requireText(stringValue(item.get("role")), "message role is required"));
        var content = item.get("content");
        if (content instanceof String text) {
            return Message.of(role, text);
        }
        if (!(content instanceof List<?> parts)) {
            throw new ResponsesValidationException("message content must be a string or an array");
        }
        var message = new Message();
        message.role = role;
        message.content = parts.stream().map(this::mapContentPart).toList();
        return message;
    }

    private Message messageFromContentPart(RoleType role, Map<String, Object> item) {
        var message = new Message();
        message.role = role;
        message.content = List.of(mapContentPart(item));
        return message;
    }

    private Content mapContentPart(Object value) {
        if (!(value instanceof Map<?, ?> rawPart)) {
            throw new ResponsesValidationException("content parts must be objects");
        }
        var part = asStringMap(rawPart);
        var type = stringValue(part.get("type"));
        if ("input_text".equals(type) || "output_text".equals(type)) {
            return Content.of(stringValue(part.get("text")));
        }
        if ("input_image".equals(type)) {
            var imageUrl = new Content.ImageUrl();
            imageUrl.url = requireText(stringValue(part.get("image_url")), "input_image.image_url is required");
            imageUrl.detail = stringValue(part.get("detail"));
            return Content.of(imageUrl);
        }
        if ("input_file".equals(type)) {
            var content = new Content();
            content.type = Content.ContentType.FILE;
            var file = new Content.FileContent();
            file.fileId = firstNonBlank(stringValue(part.get("file_id")), stringValue(part.get("file_url")));
            file.filename = stringValue(part.get("filename"));
            file.fileData = stringValue(part.get("file_data"));
            if (isBlank(file.fileId) && isBlank(file.fileData)) {
                throw new ResponsesValidationException("input_file requires file_id, file_url, or file_data");
            }
            content.file = file;
            return content;
        }
        if ("input_audio".equals(type)) {
            throw new ResponsesValidationException("input_audio is not supported by the bridge");
        }
        throw new ResponsesValidationException("unsupported content part type: " + type);
    }

    private FunctionCall mapFunctionCall(Map<String, Object> item, int index) {
        var callId = firstNonBlank(stringValue(item.get("call_id")), stringValue(item.get("id")));
        var name = requireText(stringValue(item.get("name")), "function_call.name is required");
        var arguments = stringValue(item.get("arguments"));
        var call = FunctionCall.of(requireText(callId, "function_call.call_id is required"), "function", name, arguments == null ? "" : arguments);
        call.index = index;
        return call;
    }

    private Message mapFunctionCallOutput(Map<String, Object> item) {
        var output = stringValue(item.get("output"));
        return Message.of(RoleType.TOOL, output == null ? "" : output, null,
                requireText(stringValue(item.get("call_id")), "function_call_output.call_id is required"), null);
    }

    private void flushToolCalls(List<Message> messages, List<FunctionCall> pendingToolCalls) {
        if (pendingToolCalls.isEmpty()) return;
        messages.add(Message.of(RoleType.ASSISTANT, "", null, null, List.copyOf(pendingToolCalls)));
        pendingToolCalls.clear();
    }

    private List<Tool> mapTools(List<ResponsesTool> tools) {
        if (tools == null || tools.isEmpty()) return null;
        var mapped = new ArrayList<Tool>();
        for (var responseTool : tools) {
            if (!"function".equals(responseTool.type)) {
                throw new ResponsesValidationException("only function tools are supported by the bridge");
            }
            var tool = new Tool();
            tool.type = ToolType.FUNCTION;
            tool.function = new Function();
            tool.function.name = requireText(responseTool.name, "function tool name is required");
            tool.function.description = responseTool.description == null ? "" : responseTool.description;
            tool.function.parameters = responseTool.parameters == null ? emptyObjectSchema() : responseTool.parameters;
            mapped.add(tool);
        }
        return mapped;
    }

    private String mapToolChoice(Object toolChoice) {
        if (toolChoice == null) return null;
        if (toolChoice instanceof String value) return value;
        throw new ResponsesValidationException("object tool_choice is not supported by the bridge");
    }

    @SuppressWarnings("unchecked")
    private ResponseFormat mapResponseFormat(Object text) {
        if (text == null) return null;
        if (!(text instanceof Map<?, ?> textMap)) {
            throw new ResponsesValidationException("text must be an object");
        }
        var format = ((Map<String, Object>) textMap).get("format");
        if (!(format instanceof Map<?, ?> formatMap)) return null;
        var mappedFormat = asStringMap(formatMap);
        var type = stringValue(mappedFormat.get("type"));
        if (type == null || "text".equals(type)) return null;
        if ("json_object".equals(type)) return ResponseFormat.jsonObject();
        if (!"json_schema".equals(type)) {
            throw new ResponsesValidationException("unsupported text.format.type: " + type);
        }
        var responseFormat = new ResponseFormat();
        var schema = new ResponseFormat.JsonSchemaDefinition();
        schema.name = stringValue(mappedFormat.get("name"));
        schema.strict = booleanValue(mappedFormat.get("strict"));
        schema.schema = mappedFormat.get("schema");
        responseFormat.jsonSchema = schema;
        return responseFormat;
    }

    private RoleType mapRole(String role) {
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "user" -> RoleType.USER;
            case "assistant" -> RoleType.ASSISTANT;
            case "system", "developer" -> RoleType.SYSTEM;
            default -> throw new ResponsesValidationException("unsupported message role: " + role);
        };
    }

    private boolean isInputContentPart(String type) {
        return "input_text".equals(type) || "input_image".equals(type) || "input_file".equals(type) || "input_audio".equals(type);
    }

    private JsonSchema emptyObjectSchema() {
        var schema = new JsonSchema();
        schema.type = JsonSchema.PropertyType.OBJECT;
        schema.properties = new LinkedHashMap<>();
        return schema;
    }

    private Map<String, Object> asStringMap(Map<?, ?> raw) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            var key = entry.getKey();
            if (!(key instanceof String stringKey)) {
                throw new ResponsesValidationException("object field names must be strings");
            }
            map.put(stringKey, entry.getValue());
        }
        return map;
    }

    private String requireText(String value, String message) {
        if (isBlank(value)) throw new ResponsesValidationException(message);
        return value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
