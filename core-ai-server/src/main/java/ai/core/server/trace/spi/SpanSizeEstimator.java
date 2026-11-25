package ai.core.server.trace.spi;

import io.opentelemetry.sdk.trace.ReadableSpan;

/**
 * Approximate heap held by one span while it waits in a queue: the name, attribute keys and string
 * values plus a fixed overhead. Deliberately not a protobuf serialization.
 *
 * @author core-ai
 */
final class SpanSizeEstimator {
    static long estimate(ReadableSpan span) {
        long bytes = 256 + length(span.getName());
        var attributes = span.getAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            for (var entry : attributes.asMap().entrySet()) {
                bytes += length(entry.getKey().getKey()) + 32;
                var value = entry.getValue();
                if (value instanceof String text) {
                    bytes += text.length();
                } else if (value instanceof java.util.List<?> values) {
                    bytes += values.stream().filter(String.class::isInstance)
                            .mapToLong(item -> ((String) item).length()).sum();
                } else {
                    bytes += 8;
                }
            }
        }
        return bytes;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private SpanSizeEstimator() {
    }
}
