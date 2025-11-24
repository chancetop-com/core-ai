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
    /**
     * Create a JSON response format specification.
     *
     * @return ResponseFormat configured for JSON output
     */
    public static ResponseFormat of(Class<?> cls) {
        var format = new ResponseFormat();
        format.jsonSchema = JsonUtil.toJson(JsonSchemaUtil.toJsonSchema(cls));
        return format;
    }

    @NotNull
    @Property(name = "type")
    public String type = "json_schema";

    @Property(name = "json_schema")
    public String jsonSchema;

    @Property(name = "strict")
    public Boolean strict;
}