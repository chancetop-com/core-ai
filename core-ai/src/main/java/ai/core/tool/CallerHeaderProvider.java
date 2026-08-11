package ai.core.tool;

import ai.core.tool.OutboundCallerContext.Caller;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves outbound caller headers for a given caller. Implemented by the server module
 * (reads the manager user's {@code outbound_caller_headers} config with TTL caching);
 * registered at startup via {@link #set}. When no provider is registered (or a caller has
 * no manager), injection is a no-op.
 *
 * @author stephen
 */
@FunctionalInterface
public interface CallerHeaderProvider {
    /** Resolves configured caller headers for the given caller; empty map = inject nothing. */
    Map<String, String> headersFor(Caller caller);

    AtomicReference<CallerHeaderProvider> HOLDER = new AtomicReference<>(caller -> Map.of());

    static CallerHeaderProvider get() {
        return HOLDER.get();
    }

    static void set(CallerHeaderProvider provider) {
        HOLDER.set(provider);
    }
}
