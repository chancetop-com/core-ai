package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * JSON-RPC 2.0 request envelope used by the A2A JSON-RPC transport.
 *
 * @author xander
 */
public class JsonRpcRequest<T> {
    public static final String VERSION = "2.0";

    public static <T> JsonRpcRequest<T> of(String id, String method, T params) {
        var request = new JsonRpcRequest<T>();
        request.id = id;
        request.method = method;
        request.params = params;
        return request;
    }

    @Property(name = "jsonrpc")
    public String jsonrpc = VERSION;

    @Property(name = "id")
    public Object id;

    @Property(name = "method")
    public String method;

    @Property(name = "params")
    public T params;
}
