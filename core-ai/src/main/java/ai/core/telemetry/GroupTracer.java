package ai.core.telemetry;

import ai.core.telemetry.context.GroupTraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.util.function.Supplier;

/**
 * Tracer for AgentGroup-specific operations
 * Adds group domain attributes to traces using GroupTraceContext
 *
 * @author stephen
 */
public class GroupTracer extends Tracer {
    // Langfuse input/output attributes
    private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("gen_ai.completion");

    // Custom attributes for group operations
    private static final AttributeKey<String> GROUP_NAME = AttributeKey.stringKey("group.name");
    private static final AttributeKey<String> GROUP_ID = AttributeKey.stringKey("group.id");
    private static final AttributeKey<String> GROUP_STATUS = AttributeKey.stringKey("group.status");
    private static final AttributeKey<Long> GROUP_AGENT_COUNT = AttributeKey.longKey("group.agent_count");
    private static final AttributeKey<Long> GROUP_CURRENT_ROUND = AttributeKey.longKey("group.current_round");
    private static final AttributeKey<Long> GROUP_MAX_ROUND = AttributeKey.longKey("group.max_round");
    private static final AttributeKey<String> GROUP_CURRENT_AGENT = AttributeKey.stringKey("group.current_agent");

    // Context attributes for session and user tracking
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");

    public GroupTracer(OpenTelemetry openTelemetry, boolean enabled) {
        super(openTelemetry, enabled);
    }

    /**
     * Trace group execution with context
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public <T> T traceGroupExecution(GroupTraceContext context, Supplier<T> operation) {
        if (!enabled) {
            return operation.get();
        }

        var spanBuilder = tracer.spanBuilder(INSTRUMENTATION_NAME)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "chain")
            .setAttribute(GROUP_NAME, context.getGroupName())
            .setAttribute(GROUP_ID, context.getGroupId())
            .setAttribute(GROUP_AGENT_COUNT, (long) context.getAgentCount())
            .setAttribute(GROUP_CURRENT_ROUND, (long) context.getCurrentRound())
            .setAttribute(GROUP_MAX_ROUND, (long) context.getMaxRound());

        if (context.getCurrentAgentName() != null) {
            spanBuilder.setAttribute(GROUP_CURRENT_AGENT, context.getCurrentAgentName());
        }
        if (context.getSessionId() != null) {
            spanBuilder.setAttribute(SESSION_ID, context.getSessionId());
        }
        if (context.getUserId() != null) {
            spanBuilder.setAttribute(USER_ID, context.getUserId());
        }

        var span = spanBuilder.startSpan();

        // Add input as attribute for Langfuse
        if (context.getInput() != null && !context.getInput().isEmpty()) {
            span.setAttribute(INPUT_VALUE, context.getInput());
        }

        try (var ignored = span.makeCurrent()) {
            T result = operation.get();

            // Record completion details after execution (context may be updated by operation)
            if (context.getOutput() != null) {
                span.setAttribute(OUTPUT_VALUE, context.getOutput());
            }
            if (context.getStatus() != null) {
                span.setAttribute(GROUP_STATUS, context.getStatus());
            }
            if (context.getCurrentAgentName() != null) {
                span.setAttribute(GROUP_CURRENT_AGENT, context.getCurrentAgentName());
            }

            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
