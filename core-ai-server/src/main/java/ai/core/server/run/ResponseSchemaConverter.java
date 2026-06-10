package ai.core.server.run;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.ResponseFormat;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * @author stephen
 */
public class ResponseSchemaConverter {
    /** A response schema must be a JSON object; blank / array / scalar means "no structured output", not an error. */
    public static boolean isObjectSchema(String jsonSchemaString) {
        if (jsonSchemaString == null) return false;
        String trimmed = jsonSchemaString.strip();
        return !trimmed.isEmpty() && trimmed.startsWith("{");
    }

    public static ResponseFormat fromJsonSchema(String jsonSchemaString) {
        if (!isObjectSchema(jsonSchemaString)) {
            return null;   // tolerate an empty/array/garbage schema instead of failing the whole LLM run
        }
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
