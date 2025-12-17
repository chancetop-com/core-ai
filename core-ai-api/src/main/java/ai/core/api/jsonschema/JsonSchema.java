package ai.core.api.jsonschema;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class JsonSchema {
    @NotNull
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

    @NotNull
    @Property(name = "additionalProperties")
    public Boolean additionalProperties = Boolean.FALSE;

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
