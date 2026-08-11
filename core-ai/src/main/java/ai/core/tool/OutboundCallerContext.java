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
