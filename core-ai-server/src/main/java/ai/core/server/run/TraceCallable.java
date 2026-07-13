package ai.core.server.run;

/**
 * @author stephen
 */
@FunctionalInterface
interface TraceCallable<T> {
    T call(io.opentelemetry.api.trace.Span span);
}
