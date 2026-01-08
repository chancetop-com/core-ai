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
    public static BFCLItem fromJson(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(json, BFCLItem.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }

    @JsonProperty("id")
    public String id;
    @JsonProperty("question")
    public List<List<Message>> question;
    @JsonProperty("function")
    public List<FunctionDefinition> function;


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        @JsonProperty("role")
        public String role;
        @JsonProperty("content")
        public String content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionDefinition {
        @JsonProperty("name")
        public String name;
        @JsonProperty("description")
        public String description;
        @JsonProperty("parameters")
        public Parameters parameters;
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parameters {
        @JsonProperty("type")
        public String type;
        @JsonProperty("required")
        public List<String> required;
        @JsonProperty("properties")
        public Map<String, JsonPropertyInfo> properties;

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

    }
}
