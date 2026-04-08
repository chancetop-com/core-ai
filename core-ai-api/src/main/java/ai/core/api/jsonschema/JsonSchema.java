package ai.core.api.jsonschema;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class JsonSchema {
    @Property(name = "title")
    public String title;
    @Property(name = "type")
    public PropertyType type;
    @Property(name = "description")
    public String description;
    @Property(name = "enum")
    public List<String> enums;
    @Property(name = "properties")
    public Map<String, JsonSchema> properties;
    @Property(name = "required")
    public List<String> required;
    @Property(name = "items")
    public JsonSchema items;
    @Property(name = "format")
    public String format;
    @Property(name = "additionalProperties")
    public Boolean additionalProperties;
    @Property(name = "default")
    public Object defaultValue;
    @Property(name = "const")
    public Object constValue;
    @Property(name = "minimum")
    public Number minimum;
    @Property(name = "maximum")
    public Number maximum;
    @Property(name = "minLength")
    public Integer minLength;
    @Property(name = "maxLength")
    public Integer maxLength;
    @Property(name = "pattern")
    public String pattern;
    @Property(name = "minItems")
    public Integer minItems;
    @Property(name = "maxItems")
    public Integer maxItems;
    @Property(name = "oneOf")
    public List<JsonSchema> oneOf;
    @Property(name = "anyOf")
    public List<JsonSchema> anyOf;
    @Property(name = "allOf")
    public List<JsonSchema> allOf;

    public enum PropertyType {
        @Property(name = "string")
        STRING,
        @Property(name = "number")
        NUMBER,
        @Property(name = "integer")
        INTEGER,
        @Property(name = "boolean")
        BOOLEAN,
        @Property(name = "object")
        OBJECT,
        @Property(name = "array")
        ARRAY,
        @Property(name = "null")
        NULL
    }
}
