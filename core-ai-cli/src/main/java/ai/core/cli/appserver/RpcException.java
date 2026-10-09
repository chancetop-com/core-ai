package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Serial;

/**
 * JSON-RPC error: standard code in the envelope, business code in {@code error.data.code}.
 *
 * @author stephen
 */
public class RpcException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static RpcException parseError(Throwable cause) {
        return new RpcException(PARSE_ERROR, "parse error", null, cause);
    }

    public static RpcException invalidRequest(String message) {
        return new RpcException(INVALID_REQUEST, message, null);
    }

    public static RpcException invalidParams(String message) {
        return new RpcException(INVALID_PARAMS, message, null);
    }

    public static RpcException invalidParams(String message, Throwable cause) {
        return new RpcException(INVALID_PARAMS, message, null, cause);
    }

    public static RpcException methodNotFound(String method) {
        return new RpcException(METHOD_NOT_FOUND, "method not found: " + method, null);
    }

    public static RpcException business(String businessCode, String message) {
        return new RpcException(INTERNAL_ERROR, message, dataNode(businessCode), null);
    }

    public static RpcException business(String businessCode, String message, Throwable cause) {
        return new RpcException(INTERNAL_ERROR, message, dataNode(businessCode), cause);
    }

    public static RpcException business(String businessCode, String message, String dataKey, String dataValue) {
        var data = dataNode(businessCode);
        if (dataKey != null && dataValue != null) {
            data.put(dataKey, dataValue);
        }
        return new RpcException(INTERNAL_ERROR, message, data, null);
    }

    private static ObjectNode dataNode(String businessCode) {
        var data = JsonUtil.OBJECT_MAPPER.createObjectNode();
        data.put("code", businessCode);
        return data;
    }

    private final int code;
    private final transient JsonNode data;

    public RpcException(int code, String message, JsonNode data) {
        this(code, message, data, null);
    }

    public RpcException(int code, String message, JsonNode data, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public JsonNode data() {
        return data;
    }
}
