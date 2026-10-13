package ai.core.sse;

/**
 * Bound on how much an SSE channel may buffer for a slow or half-open client. Without it a client that
 * stops reading makes the server accumulate events on the heap indefinitely. The host application
 * configures it (the server reads it from system settings); an embedder that never does keeps the
 * shipped defaults.
 *
 * @author stephen
 */
public final class SseQueueLimits {
    public static final int DEFAULT_MAX_QUEUE_DEPTH = 1000;
    public static final long DEFAULT_MAX_QUEUE_BYTES = 8L * 1024 * 1024;

    private static volatile int maxQueueDepth = DEFAULT_MAX_QUEUE_DEPTH;
    private static volatile long maxQueueBytes = DEFAULT_MAX_QUEUE_BYTES;

    public static void configure(int depth, long bytes) {
        if (depth > 0) maxQueueDepth = depth;
        if (bytes > 0) maxQueueBytes = bytes;
    }

    public static int maxQueueDepth() {
        return maxQueueDepth;
    }

    public static long maxQueueBytes() {
        return maxQueueBytes;
    }

    private SseQueueLimits() {
    }
}
