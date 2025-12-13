package ai.core.llm.domain;

import ai.core.utils.JsonSchemaUtil;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Response format specification for LLM completions.
 * Used to request structured output formats like JSON.
 *
 * @author xander
 */
public final class ResponseFormat {
    public static ResponseFormat of(Class<?> cls) {
        return of(cls, false);
    }

    public static ResponseFormat of(Class<?> cls, boolean strict) {
        var format = new ResponseFormat();
        var schemaDef = new JsonSchemaDefinition();
        schemaDef.name = cls.getSimpleName();
        schemaDef.strict = strict;
        schemaDef.schema = JsonSchemaUtil.toJsonSchema(cls);
        format.jsonSchema = JsonUtil.toJson(schemaDef);
        return format;
    }

    @NotNull
    @Property(name = "type")
    public String type = "json_schema";

    @Property(name = "json_schema")
    public String jsonSchema;

    public static class JsonSchemaDefinition {
        @Property(name = "name")
        public String name;

        @Property(name = "strict")
        public Boolean strict;

        @Property(name = "schema")
        public Object schema;
    }
}