package ai.core.api.a2a;

/**
 * Standard A2A protocol binding names used in AgentCard transport declarations.
 *
 * @author xander
 */
public final class A2ATransport {
    public static final String JSON_RPC = "JSONRPC";
    public static final String GRPC = "GRPC";
    public static final String HTTP_JSON = "HTTP+JSON";

    private A2ATransport() {
    }
}
