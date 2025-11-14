package ai.core.llm.domain;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Response format specification for LLM completions.
 * Used to request structured output formats like JSON.
 *
 * @author xander
 */
public final class ResponseFormat {
    /**
     * Create a JSON response format specification.
     *
     * @return ResponseFormat configured for JSON output
     */
    public static ResponseFormat json() {
        var format = new ResponseFormat();
        format.type = "json_object";
        return format;
    }

    /**
     * Create a JSON schema response format specification.
     *
     * @param jsonSchema the JSON schema defining the expected structure
     * @return ResponseFormat configured for JSON schema output
     */
    public static ResponseFormat jsonSchema(Map<String, Object> jsonSchema) {
        var format = new ResponseFormat();
        format.type = "json_schema";
        format.jsonSchema = jsonSchema;
        return format;
    }

    /**
     * Create a text response format specification (default).
     *
     * @return ResponseFormat configured for text output
     */
    public static ResponseFormat text() {
        var format = new ResponseFormat();
        format.type = "text";
        return format;
    }

    @Property(name = "type")
    public String type;

    @Property(name = "json_schema")
    public Map<String, Object> jsonSchema;

    private ResponseFormat() {
    }
}