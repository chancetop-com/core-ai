package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed access to request params; every failure is an {@link RpcException#INVALID_PARAMS} -32602.
 *
 * @author stephen
 */
public final class Params {
    public static String requiredText(ObjectNode params, String name) {
        var value = params.path(name).asText("");
        if (value.isBlank()) {
            throw RpcException.invalidParams(name + " is required");
        }
        return value;
    }

    public static String optionalText(ObjectNode params, String name) {
        var node = params.get(name);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    public static int intParam(ObjectNode params, String name, int fallback) {
        var node = params.get(name);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText());
        } catch (NumberFormatException e) {
            throw RpcException.invalidParams(name + " must be an integer", e);
        }
    }

    public static Integer optionalInt(ObjectNode params, String name) {
        var node = params.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        return intParam(params, name, 0);
    }

    public static List<String> stringArray(ObjectNode params, String name) {
        var node = params.get(name);
        if (node == null || !node.isArray()) {
            throw RpcException.invalidParams(name + " must be an array of strings");
        }
        var values = new ArrayList<String>(node.size());
        for (JsonNode element : node) {
            values.add(element.asText());
        }
        return values;
    }

    public static ObjectNode object() {
        return JsonUtil.OBJECT_MAPPER.createObjectNode();
    }

    private Params() {
    }
}
