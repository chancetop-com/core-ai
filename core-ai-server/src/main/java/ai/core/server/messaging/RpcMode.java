package ai.core.server.messaging;

/**
 * @author stephen
 */
public enum RpcMode {
    REDIS,
    HTTP;

    public static RpcMode of(String value) {
        return switch (value) {
            case "redis" -> REDIS;
            case "http" -> HTTP;
            default -> throw new IllegalArgumentException("sys.rpc.transport must be redis or http, value=" + value);
        };
    }
}
