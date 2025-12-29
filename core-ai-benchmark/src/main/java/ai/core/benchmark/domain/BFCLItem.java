package ai.core.benchmark.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * author: lim chen
 * date: 2025/12/19
 * description:
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BFCLItem {
    @JsonProperty("id")
    public String id;
    @JsonProperty("question")
    public List<List<Message>> question;
    @JsonProperty("function")
    public List<FunctionDefinition> function;



    public static BFCLItem fromJson(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(json, BFCLItem.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        @JsonProperty("role")
        public String role;
        @JsonProperty("content")
        public String content;

        public static Message of(String role, String content) {
            Message message = new Message();
            message.role = role;
            message.content = content;
            return message;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionDefinition {
        @JsonProperty("name")
        public String name;
        @JsonProperty("description")
        public String description;
        @JsonProperty("parameters")
        public Parameters parameters;

        public static FunctionDefinition of(String name, String description, Parameters parameters) {
            FunctionDefinition functionDefinition = new FunctionDefinition();
            functionDefinition.name = name;
            functionDefinition.description = description;
            functionDefinition.parameters = parameters;
            return functionDefinition;
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parameters {
        @JsonProperty("type")
        public String type;
        @JsonProperty("required")
        public List<String> required;
        @JsonProperty("properties")
        public Map<String, JsonPropertyInfo> properties;

        public static Parameters of(String type, List<String> required, Map<String, JsonPropertyInfo> properties) {
            Parameters parameters = new Parameters();
            parameters.type = type;
            parameters.required = required;
            parameters.properties = properties;
            return parameters;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonPropertyInfo {
        @JsonProperty("type")
        public String type;
        @JsonProperty("description")
        public String description;
        @JsonProperty("default")
        public Object defaultValue; // 使用 Object 以兼容不同类型
        @JsonProperty("enum")
        public List<String> enumValues;
        @JsonProperty("properties")
        public Map<String, JsonPropertyInfo> properties;
        @JsonProperty("items")
        public JsonPropertyInfo items;

        public static JsonPropertyInfo of(String type, String description, Object defaultValue,
                                          List<String> enumValues, Map<String, JsonPropertyInfo> properties) {
            JsonPropertyInfo JsonPropertyInfo = new JsonPropertyInfo();
            JsonPropertyInfo.type = type;
            JsonPropertyInfo.description = description;
            JsonPropertyInfo.defaultValue = defaultValue;
            JsonPropertyInfo.enumValues = enumValues;
            JsonPropertyInfo.properties = properties;
            return JsonPropertyInfo;
        }
    }
}
