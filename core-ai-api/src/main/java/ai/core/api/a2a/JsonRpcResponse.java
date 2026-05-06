package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * JSON-RPC 2.0 response envelope used by the A2A JSON-RPC transport.
 *
 * @author xander
 */
public class JsonRpcResponse<T> {
    public static <T> JsonRpcResponse<T> success(Object id, T result) {
        var response = new JsonRpcResponse<T>();
        response.id = id;
        response.result = result;
        return response;
    }

    public static <T> JsonRpcResponse<T> failure(Object id, JsonRpcError error) {
        var response = new JsonRpcResponse<T>();
        response.id = id;
        response.error = error;
        return response;
    }

    @Property(name = "jsonrpc")
    public String jsonrpc = JsonRpcRequest.VERSION;

    @Property(name = "id")
    public Object id;

    @Property(name = "result")
    public T result;

    @Property(name = "error")
    public JsonRpcError error;
}
