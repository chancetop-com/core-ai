package ai.core.api.mcp;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class JsonRpcResponse {
    public static JsonRpcResponse of(String jsonrpc, Object id) {
        var rsp = new JsonRpcResponse();
        rsp.jsonrpc = jsonrpc;
        rsp.id = id;
        return rsp;
    }

    @Property(name = "jsonrpc")
    public String jsonrpc;

    @Property(name = "id")
    public Object id;

    @Property(name = "result")
    public Object result;

    @Property(name = "error")
    public JsonRpcError error;
}
