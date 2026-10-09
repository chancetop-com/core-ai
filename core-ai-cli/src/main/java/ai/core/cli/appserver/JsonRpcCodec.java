package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Newline-delimited JSON-RPC 2.0 framing for the app-server transport: parse one line into a
 * {@link Request}, build response / error / notification frames.
 *
 * @author stephen
 */
public final class JsonRpcCodec {
    public static final String JSONRPC_VERSION = "2.0";

    public static Request parse(String line) {
        JsonNode root;
        try {
            root = JsonUtil.OBJECT_MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw RpcException.parseError(e);
        }
        if (root == null || !root.isObject()) {
            throw RpcException.invalidRequest("request must be a JSON object");
        }
        if (!JSONRPC_VERSION.equals(root.path("jsonrpc").asText())) {
            throw RpcException.invalidRequest("jsonrpc must be \"" + JSONRPC_VERSION + "\"");
        }
        var methodNode = root.get("method");
        if (methodNode == null || !methodNode.isTextual() || methodNode.asText().isBlank()) {
            throw RpcException.invalidRequest("method must be a non-empty string");
        }
        JsonNode id = root.get("id");
        if (id != null && id.isNull()) {
            id = null;
        } else if (id != null && !id.isTextual() && !id.isNumber()) {
            throw RpcException.invalidRequest("id must be a string or a number");
        }
        ObjectNode params = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var paramsNode = root.get("params");
        if (paramsNode != null && !paramsNode.isNull()) {
            if (!paramsNode.isObject()) {
                throw RpcException.invalidRequest("params must be an object");
            }
            params = (ObjectNode) paramsNode;
        }
        return new Request(methodNode.asText(), id, params);
    }

    public static ObjectNode response(JsonNode id, JsonNode result) {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC_VERSION);
        node.set("id", id == null ? JsonUtil.OBJECT_MAPPER.nullNode() : id);
        node.set("result", result == null ? JsonUtil.OBJECT_MAPPER.createObjectNode() : result);
        return node;
    }

    public static ObjectNode error(JsonNode id, int code, String message, JsonNode data) {
        var error = JsonUtil.OBJECT_MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        if (data != null && !data.isNull()) {
            error.set("data", data);
        }
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC_VERSION);
        node.set("id", id == null ? JsonUtil.OBJECT_MAPPER.nullNode() : id);
        node.set("error", error);
        return node;
    }

    public static ObjectNode notification(String method, JsonNode params) {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("jsonrpc", JSONRPC_VERSION);
        node.put("method", method);
        if (params != null && !params.isNull()) {
            node.set("params", params);
        }
        return node;
    }

    public static ObjectNode emptyParams() {
        return JsonUtil.OBJECT_MAPPER.createObjectNode();
    }

    private JsonRpcCodec() {
    }

    public record Request(String method, JsonNode id, ObjectNode params) {
        public boolean notification() {
            return id == null;
        }
    }
}
