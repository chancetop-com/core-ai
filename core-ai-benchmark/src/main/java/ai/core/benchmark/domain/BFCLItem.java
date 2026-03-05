package ai.core.benchmark.domain;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

public class BFCLItem {
    @Property(name = "id")
    public String id;
    @Property(name = "question")
    public List<List<Message>> question;
    @Property(name = "function")
    public List<FunctionDefinition> function;

    public static class Message {
        @Property(name = "role")
        public String role;
        @Property(name = "content")
        public String content;
    }

    public static class FunctionDefinition {
        @Property(name = "name")
        public String name;
        @Property(name = "description")
        public String description;
        @Property(name = "parameters")
        public Parameters parameters;
    }

    public static class Parameters {
        @Property(name = "type")
        public String type;
        @Property(name = "required")
        public List<String> required;
        @Property(name = "properties")
        public Map<String, JsonPropertyInfo> properties;
    }

    public static class JsonPropertyInfo {
        @Property(name = "type")
        public String type;
        @Property(name = "description")
        public String description;
        @Property(name = "default")
        public Object defaultValue;
        @Property(name = "enum")
        public List<String> enumValues;
        @Property(name = "properties")
        public Map<String, JsonPropertyInfo> properties;
        @Property(name = "items")
        public JsonPropertyInfo items;
    }
}
