package ai.core.api.mcp;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class JsonRpcError {
    @Property(name = "code")
    public Integer code;

    @Property(name = "message")
    public String message;

    @Property(name = "data")
    public String data;
}
