package ai.core.api.mcp;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class JsonRpcNotification {
    @Property(name = "jsonrpc")
    public String jsonrpc;

    @Property(name = "method")
    public String method;

    @Property(name = "params")
    public String params;
}
