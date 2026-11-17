package ai.core.server.messaging;

/**
 * @author stephen
 */
public enum SseDeliveryMode {
    REDIS,
    DIRECTED;

    public static SseDeliveryMode of(String value) {
        if (value == null || value.isBlank() || "redis".equalsIgnoreCase(value)) {
            return REDIS;
        }
        if ("directed".equalsIgnoreCase(value)) {
            return DIRECTED;
        }
        throw new IllegalArgumentException("invalid sys.sse.delivery, expected redis|directed but was " + value);
    }
}
