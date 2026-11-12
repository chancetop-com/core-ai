package ai.core.server.util;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;

/**
 * PostgreSQL text and jsonb cannot contain NUL; tool output and transcripts occasionally do.
 *
 * @author stephen
 */
public final class JsonSanitizer {
    public static String text(String value) {
        return value == null || value.indexOf('\0') < 0 ? value : value.replace("\0", "");
    }

    /**
     * NUL must be removed from the JSON tree, not from the serialized text: a literal "\u0000" in
     * the source is serialized as "\\u0000", and a blind replace would leave a dangling backslash.
     */
    public static String json(String json) {
        if (json.indexOf("\\u0000") < 0) return json;
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(json);
            return JsonUtil.OBJECT_MAPPER.writeValueAsString(stripNul(node));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode stripNul(JsonNode node) {
        if (node.isTextual()) {
            var text = node.textValue();
            return text.indexOf('\0') < 0 ? node : TextNode.valueOf(text.replace("\0", ""));
        }
        if (node.isObject()) {
            var object = (ObjectNode) node;
            var fields = new ArrayList<>(object.properties());
            for (var field : fields) object.set(field.getKey(), stripNul(field.getValue()));
            return object;
        }
        if (node.isArray()) {
            var array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) array.set(i, stripNul(array.get(i)));
            return array;
        }
        return node;
    }

    private JsonSanitizer() {
    }
}
