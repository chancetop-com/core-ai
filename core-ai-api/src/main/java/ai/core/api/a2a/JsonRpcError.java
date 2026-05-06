package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * JSON-RPC 2.0 error object.
 *
 * @author xander
 */
public class JsonRpcError {
    public static JsonRpcError of(int code, String message) {
        var error = new JsonRpcError();
        error.code = code;
        error.message = message;
        return error;
    }

    public static JsonRpcError of(int code, String message, Object data) {
        var error = of(code, message);
        error.data = data;
        return error;
    }

    @Property(name = "code")
    public Integer code;

    @Property(name = "message")
    public String message;

    @Property(name = "data")
    public Object data;
}
