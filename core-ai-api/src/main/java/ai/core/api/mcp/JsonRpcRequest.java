package ai.core.api.mcp;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class JsonRpcRequest {
    @Property(name = "jsonrpc")
    public String jsonrpc;

    @Property(name = "method")
    public MethodEnum method;

    @Property(name = "id")
    public String id;

    @Property(name = "params")
    public String params;
}
