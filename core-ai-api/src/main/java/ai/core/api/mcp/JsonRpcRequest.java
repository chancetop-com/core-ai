package ai.core.api.mcp;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class JsonRpcRequest {
    public static JsonRpcRequest of(String jsonrpc, MethodEnum method, Object id, Object params) {
        var request = new JsonRpcRequest();
        request.jsonrpc = jsonrpc;
        request.method = method;
        request.id = id;
        request.params = params;
        return request;
    }

    @Property(name = "jsonrpc")
    public String jsonrpc;

    @Property(name = "method")
    public MethodEnum method;

    @Property(name = "id")
    public Object id;

    @Property(name = "params")
    public Object params;
}
