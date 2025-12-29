package ai.core.benchmark.inference;

import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemEvalResult;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
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
        ObjectMapper mapper = new ObjectMapper();
        var functions = item.function;
        return functions.stream().map(function -> {
            var tool = new Tool();
            tool.type = ToolType.FUNCTION;
            try {
                convertParameters(function.parameters);
                var funcJson = mapper.writeValueAsString(function);
                tool.function = JsonUtil.fromJson(Function.class, funcJson);
                return tool;
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

        }).toList();
    }
    private String convertType(String type){
        var tempType = type.toLowerCase();
        return switch (tempType) {
            case "dict", "any", "hashmap" -> "object";
            default -> tempType;
        };
    }

    private void convertParameters(BFCLItem.Parameters parameters) {
        parameters.type = convertType(parameters.type);
        var properties = parameters.properties;
        if (properties != null) {
            properties.values().forEach(this::convertPropertyInfo);
        }
    }

    private void convertPropertyInfo(BFCLItem.JsonPropertyInfo propertyInfo) {
        if (propertyInfo == null) {
            return;
        }

        // Convert the type field
        if (propertyInfo.type != null) {
            propertyInfo.type = convertType(propertyInfo.type);
        }

        // Recursively convert nested properties (for object types)
        if (propertyInfo.properties != null) {
            propertyInfo.properties.values().forEach(this::convertPropertyInfo);
        }

        // Recursively convert items (for array types)
        if (propertyInfo.items != null) {
            convertPropertyInfo(propertyInfo.items);
        }
    }

    private List<Message> completeMessages(BFCLItem item) {
        var user = Message.of(RoleType.USER, item.question.getFirst().getFirst().content);
        return List.of(user);
    }


}
