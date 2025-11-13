package ai.core.llm.domain;

import core.framework.api.json.Property;

/**
 * Response format for LLM completion requests.
 * Used to specify the desired output format (e.g., JSON).
 *
 * @author stephen
 */
public class ResponseFormat {
    public static ResponseFormat json() {
        var format = new ResponseFormat();
        format.type = "json_object";
        return format;
    }

    public static ResponseFormat text() {
        var format = new ResponseFormat();
        format.type = "text";
        return format;
    }

    @Property(name = "type")
    public String type;
}