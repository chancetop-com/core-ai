package ai.core.benchmark.inference;

import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemEvalResult;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.util.Strings;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * author: lim chen
 * date: 2025/12/22
 * description:
 */
public abstract class BFCLInferenceHandle implements InferenceHandle<BFCLItem, BFCLItemEvalResult> {

    @Override
    public BFCLItemEvalResult handle(BFCLItem item) {
        var tools = completeTools(item);
        var messages = completeMessages(item);
        return invoke(item.id, messages, tools);

    }

    protected abstract BFCLItemEvalResult invoke(String id, List<Message> messages, List<Tool> tools);


    private List<Tool> completeTools(BFCLItem item) {

        var functions = item.function;
        var itemId = item.id;
        return functions.stream()
                .map(this::toJson)
                .map(this::readFunctionDefValue)
                .peek(function -> function.name = function.name.replaceAll("\\.", "_"))
                .peek(function -> wrapDesc(itemId, function))
                .map(function -> {
                    var tool = new Tool();
                    tool.type = ToolType.FUNCTION;
                    convertParameters(function.parameters, itemId);
                    var funcJson = toJson(function);
                    tool.function = JsonUtil.fromJson(Function.class, funcJson);
                    return tool;
                }).toList();
    }

    private void wrapDesc(String itemId, BFCLItem.FunctionDefinition definition) {
        if (isJava(itemId)) {
            definition.description += " Note that the provided function is in Java 8 SDK syntax.";
        } else if (isJs(itemId)) {
            definition.description += " Note that the provided function is in JavaScript syntax.";
        } else {
            definition.description += " Note that the provided function is in Python 3 syntax.";
        }

    }

    private boolean isJava(String itemId) {
        return itemId.contains("java") && !itemId.contains("javascript");
    }

    private boolean isJs(String itemId) {
        return itemId.contains("javascript");
    }

    private BFCLItem.FunctionDefinition readFunctionDefValue(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(json, BFCLItem.FunctionDefinition.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String toJson(Object obj) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String convertType(String type) {
        var tempType = type.toLowerCase(Locale.ROOT);
        if (Strings.isBlank(tempType)) {
            return "object";
        }
        return switch (tempType) {
            case "dict", "hashmap", "hashtable" -> "object";
            case "double", "float" -> "number";
            case "arraylist", "list", "tuple", "queue", "stack" -> "array";
            case "bool" -> "boolean";
            case "long", "byte", "short", "bigint" -> "integer";
            case "char", "any" -> "string";
            default -> tempType;
        };
    }

    private void convertParameters(BFCLItem.Parameters parameters, String itemId) {
        parameters.type = "object";
        var properties = parameters.properties;
        if (properties != null) {
            properties.values().forEach(p -> convertPropertyInfo(p, itemId));
        }
    }

    private void convertPropertyInfo(BFCLItem.JsonPropertyInfo propertyInfo, String itemId) {
        final String tempId = itemId;
        if (propertyInfo == null) {
            return;
        }

        // Convert the type field
        if (propertyInfo.type != null) {
            if (isJava(tempId)) {
                propertyInfo.description = mappingPropJAVADesc(propertyInfo.type, propertyInfo);
                propertyInfo.description = mappingDelPropJavaList(propertyInfo.type, propertyInfo);
                propertyInfo.type = "string";
            }
            if (isJs(tempId)) {
                propertyInfo.description = mappingPropJSDesc(propertyInfo.type, propertyInfo);
                propertyInfo.description = mappingDelPropJSList(propertyInfo.type, propertyInfo);
                propertyInfo.type = "string";
            }

            propertyInfo.type = convertType(propertyInfo.type);
        }

        // Recursively convert nested properties (for object types)
        if (propertyInfo.properties != null) {
            propertyInfo.properties.values().forEach(p -> convertPropertyInfo(p, tempId));
        }

        // Recursively convert items (for array types)
        if (propertyInfo.items != null) {
            convertPropertyInfo(propertyInfo.items, tempId);
        }
    }

    private String mappingPropJAVADesc(String type, BFCLItem.JsonPropertyInfo propertyInfo) {
        String desc = propertyInfo.description;
        if (Objects.equals(type, "any")) {
            return desc + " This parameter can be of any type of Java object in string representation.";
        } else {
            return desc + " This is Java %s type parameter in string representation.".formatted(type);
        }
    }

    private String mappingPropJSDesc(String type, BFCLItem.JsonPropertyInfo propertyInfo) {
        String desc = propertyInfo.description;
        if (Objects.equals(type, "any")) {
            return desc + " This parameter can be of any type of JavaScript object in string representation.";
        } else {
            return desc + " This is JavaScript %s type parameter in string representation.".formatted(type);
        }
    }

    private String mappingDelPropJavaList(String type, BFCLItem.JsonPropertyInfo propertyInfo) {
        String desc = propertyInfo.description;
        if (Objects.equals(type, "ArrayList") || Objects.equals(type, "Array")) {
            desc = desc + "The list elements are of type %s; they are not in string representation.".formatted(propertyInfo.items.type);
            propertyInfo.items = null;
        }
        return desc;
    }

    private String mappingDelPropJSList(String type, BFCLItem.JsonPropertyInfo propertyInfo) {
        StringBuilder desc = new StringBuilder(propertyInfo.description);
        if (Objects.equals(type, "array")) {
            desc.append("The list elements are of type %s; they are not in string representation.".formatted(propertyInfo.items.type));
            propertyInfo.items = null;
        }

        if (Objects.equals(type, "dict") && propertyInfo.properties != null && !propertyInfo.properties.isEmpty()) {
            desc.append("The dictionary entries have the following schema; they are not in string representation. %s".formatted(toJson(propertyInfo.properties)));
            propertyInfo.properties = null;
        }
        return desc.toString();
    }

    private List<Message> completeMessages(BFCLItem item) {
        var user = Message.of(RoleType.USER, item.question.getFirst().getFirst().content);
        return List.of(user);
    }


}
