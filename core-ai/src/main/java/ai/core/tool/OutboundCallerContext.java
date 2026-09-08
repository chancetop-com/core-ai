package ai.core.tool;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

import java.util.Map;

/**
 * Caller identity of the current tool execution, propagated through the OpenTelemetry Context
 * so it survives async thread hops (ToolExecutor runs tools via {@code supplyAsync} with
 * {@code otelContext.makeCurrent()}).
 *
 * <p>Values are resolved from the authenticated user at session/run creation; LLM and agents
 * cannot influence them. Outbound tool HTTP calls (API tools, MCP tools) read the current
 * caller via {@link #current()} and inject configured headers through {@link CallerHeaderProvider}.
 *
 * @author stephen
 */
public final class OutboundCallerContext {
    private static final ContextKey<Caller> CALLER_KEY = ContextKey.named("outbound-caller");

    /** Makes the given caller current for the calling thread; close the scope when done. */
    public static Scope set(Caller caller) {
        return Context.current().with(CALLER_KEY, caller).makeCurrent();
    }

    /** Runs the action with the caller scope current on this thread (no-op scope when caller is null). */
    @SuppressWarnings("try")
    public static void runWith(Caller caller, Runnable action) {
        if (caller == null) {
            action.run();
            return;
        }
        try (var ignored = set(caller)) {
            action.run();
        }
    }

    /** Runs the supplier with the caller scope current on this thread (no-op scope when caller is null). */
    @SuppressWarnings("try")
    public static <T> T runWith(Caller caller, java.util.function.Supplier<T> action) {
        if (caller == null) {
            return action.get();
        }
        try (var ignored = set(caller)) {
            return action.get();
        }
    }

    /** Current caller, or null outside tool execution / when no caller context was set. */
    public static Caller current() {
        return Context.current().get(CALLER_KEY);
    }

    private OutboundCallerContext() {
    }

    public record Caller(String externalId, String userId, String managerId, Map<String, String> metadata) {
        public static Caller empty() {
            return new Caller(null, null, null, null);
        }
    }
}
