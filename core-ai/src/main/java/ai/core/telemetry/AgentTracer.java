package ai.core.telemetry;

import ai.core.telemetry.context.AgentTraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;

import java.util.function.BooleanSupplier;
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
    private static final AttributeKey<Boolean> TOOL_IS_SUB_AGENT = AttributeKey.booleanKey("tool.is_sub_agent");

    public AgentTracer(OpenTelemetry openTelemetry, boolean enabled) {
        super(openTelemetry, enabled);
    }

    /**
     * Trace agent execution with context
     * Follows OpenTelemetry semantic conventions for GenAI agent spans
     */
    @SuppressWarnings("try")
    public <T> T traceAgentExecution(AgentTraceContext context, Supplier<T> operation) {
        return traceAgentExecution(context, operation, null);
    }

    /**
     * Trace agent execution and mark the span as user-cancelled when the caller reports cancellation.
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public <T> T traceAgentExecution(AgentTraceContext context, Supplier<T> operation, BooleanSupplier cancellationSupplier) {
        if (!enabled) {
            return operation.get();
        }

        var span = createAgentSpan(context);

        if (context.getInput() != null && !context.getInput().isEmpty()) {
            span.setAttribute(INPUT_VALUE, context.getInput());
        }

        try (var scope = span.makeCurrent()) {
            T result = operation.get();

            if (context.getOutput() != null) {
                span.setAttribute(OUTPUT_VALUE, context.getOutput());
            }
            if (context.getStatus() != null) {
                span.setAttribute(AGENT_STATUS, context.getStatus());
            }
            if (isCancelled(cancellationSupplier)) {
                markCancelled(span);
                span.setAttribute(AGENT_STATUS, "CANCELLED");
            }
            if (context.getMessageCount() > 0) {
                span.setAttribute(AGENT_MESSAGE_COUNT, (long) context.getMessageCount());
            }

            return result;
        } catch (Exception e) {
            if (isCancelled(cancellationSupplier)) {
                markCancelled(span);
                span.setAttribute(AGENT_STATUS, "CANCELLED");
                throw e;
            }
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private Span createAgentSpan(AgentTraceContext context) {
        var spanBuilder = tracer.spanBuilder("agent.turn")
            .setSpanKind(SpanKind.CLIENT)
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

        return spanBuilder.startSpan();
    }

    /**
     * Trace tool/function call with ToolCallResult
     */
    public <T> T traceToolCall(String toolName, String arguments, Supplier<T> operation) {
        return traceToolCall(toolName, arguments, null, operation);
    }

    /**
     * Trace tool/function call with an explicit parent span context.
     * When parentSpanContext is provided and valid, the tool span will be nested under it
     * instead of the current OpenTelemetry context. This is used to nest tool spans under
     * the LLM span that triggered them (Langfuse-style causal chain).
     */
    public <T> T traceToolCall(String toolName, String arguments, SpanContext parentSpanContext, Supplier<T> operation) {
        return traceToolCall(toolName, arguments, parentSpanContext, false, operation);
    }

    /**
     * Trace tool/function call, marking the span as a sub-agent delegation when applicable so the
     * trace UI can tell a "call another agent" tool span apart from a regular tool span.
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public <T> T traceToolCall(String toolName, String arguments, SpanContext parentSpanContext, boolean isSubAgent, Supplier<T> operation) {
        if (!enabled) {
            return operation.get();
        }

        var spanBuilder = tracer.spanBuilder(toolName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "tool")
            .setAttribute(GEN_AI_OPERATION_NAME, "tool")
            .setAttribute(TOOL_NAME, toolName);

        if (isSubAgent) {
            spanBuilder.setAttribute(TOOL_IS_SUB_AGENT, true);
        }

        if (parentSpanContext != null && parentSpanContext.isValid()) {
            spanBuilder.setParent(Context.current().with(Span.wrap(parentSpanContext)));
        }

        var span = spanBuilder.startSpan();

        // Add input as attribute for Langfuse (tool arguments)
        if (arguments != null && !arguments.isEmpty()) {
            span.setAttribute(INPUT_VALUE, arguments);
        }

        try (var scope = span.makeCurrent()) {
            T result = operation.get();

            // Add output as attribute for Langfuse (tool result)
            if (result != null) {
                span.setAttribute(OUTPUT_VALUE, result.toString());
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
                currentSpan.setAttribute(OUTPUT_VALUE, output);
            }
            currentSpan.setAttribute(AGENT_STATUS, status);
            currentSpan.setAttribute(AGENT_MESSAGE_COUNT, (long) messageCount);
        }
    }
}
