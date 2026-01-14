package ai.core.llm.domain;

import ai.core.utils.JsonSchemaUtil;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
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
        format.jsonSchema = schemaDef;
        return format;
    }

    public static ResponseFormat jsonObject() {
        var format = new ResponseFormat();
        format.type = "json_object";
        format.jsonSchema = null;
        return format;
    }

    @NotNull
    @Property(name = "type")
    public String type = "json_schema";

    @Property(name = "json_schema")
    public JsonSchemaDefinition jsonSchema;

    public static class JsonSchemaDefinition {
        @Property(name = "name")
        public String name;

        @Property(name = "strict")
        public Boolean strict;

        @Property(name = "schema")
        public Object schema;
    }
}