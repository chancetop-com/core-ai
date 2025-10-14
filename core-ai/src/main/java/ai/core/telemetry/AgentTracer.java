package ai.core.telemetry;

import ai.core.telemetry.context.AgentTraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.util.function.Supplier;

/**
 * Tracer for Agent-specific operations
 * Adds agent domain attributes to traces using AgentTraceContext
 * Follows OpenTelemetry Semantic Conventions for GenAI Agent Spans
 *
 * @author stephen
 */
public class AgentTracer extends Tracer {
    // OpenTelemetry semantic convention attributes for GenAI agents
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> GEN_AI_AGENT_ID = AttributeKey.stringKey("gen_ai.agent.id");
    private static final AttributeKey<String> GEN_AI_AGENT_DESCRIPTION = AttributeKey.stringKey("gen_ai.agent.description");
    private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("gen_ai.completion");

    // Custom attributes for additional agent information
    private static final AttributeKey<Long> AGENT_MESSAGE_COUNT = AttributeKey.longKey("agent.message_count");
    private static final AttributeKey<String> AGENT_STATUS = AttributeKey.stringKey("agent.status");
    private static final AttributeKey<Boolean> AGENT_HAS_TOOLS = AttributeKey.booleanKey("agent.has_tools");
    private static final AttributeKey<Boolean> AGENT_HAS_RAG = AttributeKey.booleanKey("agent.has_rag");

    // Context attributes for session and user tracking
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");

    // Tool call attributes
    private static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("tool.name");

    public AgentTracer(OpenTelemetry openTelemetry, boolean enabled) {
        super(openTelemetry, enabled);
    }

    /**
     * Trace agent execution with context
     * Follows OpenTelemetry semantic conventions for GenAI agent spans
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public <T> T traceAgentExecution(AgentTraceContext context, Supplier<T> operation) {
        if (!enabled) {
            return operation.get();
        }

        var spanBuilder = tracer.spanBuilder(INSTRUMENTATION_NAME)
            .setSpanKind(SpanKind.CLIENT)  // CLIENT per OTel conventions for agent operations
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "agent")
            .setAttribute(GEN_AI_OPERATION_NAME, "agent")
            .setAttribute(GEN_AI_AGENT_NAME, context.getName())
            .setAttribute(GEN_AI_AGENT_ID, context.getId())
            .setAttribute(AGENT_HAS_TOOLS, context.hasTools())
            .setAttribute(AGENT_HAS_RAG, context.hasRag());

        if (context.getType() != null) {
            spanBuilder.setAttribute(GEN_AI_AGENT_DESCRIPTION, context.getType());
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
            span.setAttribute(INPUT_VALUE, truncate(context.getInput(), 1000));
        }

        try (var scope = span.makeCurrent()) {
            T result = operation.get();

            // Record completion details after execution (context may be updated by operation)
            if (context.getOutput() != null) {
                span.setAttribute(OUTPUT_VALUE, truncate(context.getOutput(), 1000));
            }
            if (context.getStatus() != null) {
                span.setAttribute(AGENT_STATUS, context.getStatus());
            }
            if (context.getMessageCount() > 0) {
                span.setAttribute(AGENT_MESSAGE_COUNT, (long) context.getMessageCount());
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

    /**
     * Trace tool/function call
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public String traceToolCall(String toolName, String arguments, Supplier<String> operation) {
        if (!enabled) {
            return operation.get();
        }

        var span = tracer.spanBuilder(toolName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "tool")
            .setAttribute(GEN_AI_OPERATION_NAME, "tool")
            .setAttribute(TOOL_NAME, toolName)
            .startSpan();

        // Add input as attribute for Langfuse (tool arguments)
        if (arguments != null && !arguments.isEmpty()) {
            span.setAttribute(INPUT_VALUE, truncate(arguments, 1000));
        }

        try (var scope = span.makeCurrent()) {
            String result = operation.get();

            // Add output as attribute for Langfuse (tool result)
            if (result != null && !result.isEmpty()) {
                span.setAttribute(OUTPUT_VALUE, truncate(result, 1000));
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

    /**
     * Record agent completion details on current span
     */
    public void recordAgentCompletion(String output, String status, int messageCount) {
        if (!enabled) return;

        var currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            if (output != null && !output.isEmpty()) {
                currentSpan.setAttribute(OUTPUT_VALUE, truncate(output, 1000));
            }
            currentSpan.setAttribute(AGENT_STATUS, status);
            currentSpan.setAttribute(AGENT_MESSAGE_COUNT, (long) messageCount);
        }
    }
}
