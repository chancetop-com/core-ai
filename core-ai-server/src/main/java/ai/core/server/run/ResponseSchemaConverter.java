package ai.core.server.run;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.ResponseFormat;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * @author stephen
 */
public class ResponseSchemaConverter {
    public static ResponseFormat fromJsonSchema(String jsonSchemaString) {
        JsonSchema schema = JsonUtil.fromJson(new TypeReference<>() { }, jsonSchemaString);
        return fromJsonSchema(schema);
    }

    public static ResponseFormat fromJsonSchema(JsonSchema schema) {
        var format = new ResponseFormat();
        var schemaDef = new ResponseFormat.JsonSchemaDefinition();
        schemaDef.name = schema.title != null ? schema.title : "response";
        schemaDef.strict = false;
        schemaDef.schema = schema;
        format.jsonSchema = schemaDef;
        return format;
    }
}
