package ai.core.telemetry;

import ai.core.telemetry.context.FlowTraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.util.function.Supplier;

/**
 * Tracer for Flow-specific operations
 * Adds flow domain attributes to traces using FlowTraceContext
 *
 * @author stephen
 */
public class FlowTracer extends Tracer {
    // Custom attributes for flow operations
    private static final AttributeKey<String> FLOW_ID = AttributeKey.stringKey("flow.id");
    private static final AttributeKey<String> FLOW_NAME = AttributeKey.stringKey("flow.name");
    private static final AttributeKey<String> NODE_ID = AttributeKey.stringKey("flow.node.id");
    private static final AttributeKey<String> NODE_NAME = AttributeKey.stringKey("flow.node.name");

    // Context attributes for session and user tracking
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");

    public FlowTracer(OpenTelemetry openTelemetry, boolean enabled) {
        super(openTelemetry, enabled);
    }

    /**
     * Trace flow execution with context
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public <T> T traceFlowExecution(FlowTraceContext context, Supplier<T> operation) {
        if (!enabled) {
            return operation.get();
        }

        var spanBuilder = tracer.spanBuilder(INSTRUMENTATION_NAME)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(FLOW_ID, context.getFlowId())
            .setAttribute(FLOW_NAME, context.getFlowName())
            .setAttribute(NODE_ID, context.getNodeId());

        if (context.getNodeName() != null) {
            spanBuilder.setAttribute(NODE_NAME, context.getNodeName());
        }
        if (context.getSessionId() != null) {
            spanBuilder.setAttribute(SESSION_ID, context.getSessionId());
        }
        if (context.getUserId() != null) {
            spanBuilder.setAttribute(USER_ID, context.getUserId());
        }

        var span = spanBuilder.startSpan();

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
}
