package ai.core.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.util.function.Supplier;

/**
 * Abstract base class for tracing operations
 * Provides core OpenTelemetry functionality that can be extended for domain-specific tracing
 *
 * @author stephen
 */
public abstract class Tracer {
    protected static final String INSTRUMENTATION_NAME = "CoreAI";
    protected static final String INSTRUMENTATION_VERSION = "1.0.0";

    // Attribute key for langfuse
    static final AttributeKey<String> LANGFUSE_OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");

    protected final io.opentelemetry.api.trace.Tracer tracer;
    protected final boolean enabled;

    protected Tracer(OpenTelemetry openTelemetry, boolean enabled) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
        this.enabled = enabled;
    }

    /**
     * Check if tracing is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the underlying OpenTelemetry tracer
     */
    public io.opentelemetry.api.trace.Tracer getTracer() {
        return tracer;
    }

    /**
     * Get current span
     */
    public Span getCurrentSpan() {
        return Span.current();
    }

    /**
     * Create a new span as a child of current context
     */
    public Span startSpan(String spanName) {
        if (!enabled) {
            return Span.getInvalid();
        }
        return tracer.spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
    }

    /**
     * Trace an operation with a custom span
     * Subclasses can override to add domain-specific attributes
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    protected <T> T trace(String spanName, SpanKind spanKind, Supplier<T> operation) {
        if (!enabled) {
            return operation.get();
        }

        var span = tracer.spanBuilder(spanName)
            .setSpanKind(spanKind)
            .startSpan();

        try (var ignored = span.makeCurrent()) {
            return operation.get();
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Truncate text to a maximum length
     */
    protected String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
