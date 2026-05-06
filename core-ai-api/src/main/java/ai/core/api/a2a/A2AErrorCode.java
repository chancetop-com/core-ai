package ai.core.api.a2a;

/**
 * Common JSON-RPC and A2A-specific error codes.
 *
 * @author xander
 */
public final class A2AErrorCode {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static final int TASK_NOT_FOUND = -32001;
    public static final int TASK_NOT_CANCELABLE = -32002;
    public static final int PUSH_NOTIFICATION_NOT_SUPPORTED = -32003;
    public static final int UNSUPPORTED_OPERATION = -32004;
    public static final int CONTENT_TYPE_NOT_SUPPORTED = -32005;
    public static final int INVALID_AGENT_RESPONSE = -32006;
    public static final int AUTHENTICATED_EXTENDED_CARD_NOT_CONFIGURED = -32007;

    private A2AErrorCode() {
    }
}
