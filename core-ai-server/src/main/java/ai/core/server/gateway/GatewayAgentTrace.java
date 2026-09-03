package ai.core.server.gateway;

import ai.core.telemetry.TelemetryConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class GatewayAgentTrace {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayAgentTrace.class);
    private static final AttributeKey<String> LANGFUSE_OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    private static final AttributeKey<String> LANGFUSE_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> CLIENT_TYPE = AttributeKey.stringKey("client.type");
    private static final AttributeKey<String> GEN_AI_AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("tool.name");
    private static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("tool.call_id");
    private static final AttributeKey<String> GEN_AI_PROMPT = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> GEN_AI_COMPLETION = AttributeKey.stringKey("gen_ai.completion");
    private static final int MAX_TOOL_ARGUMENTS_LENGTH = 4000;
    private static final int MAX_TOOL_RESULT_LENGTH = 8000;

    static GatewaySpanRoot startSpans(TelemetryConfig telemetry, GatewayUpstreamCall call, TraceRequest request) {
        if (telemetry == null || !telemetry.isEnabled()) {
            var noop = OpenTelemetry.noop().getTracer("core-ai-server").spanBuilder("gateway").startSpan();
            return new GatewaySpanRoot(noop, noop);
        }
        var tracer = telemetry.getOpenTelemetry().getTracer("core-ai-server");
        Span root = null;
        if (GatewaySupport.hasText(request.agentName())) {
            var rootBuilder = tracer.spanBuilder(request.agentName())
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute(CLIENT_TYPE, "gateway")
                    .setAttribute(LANGFUSE_OBSERVATION_TYPE, "agent")
                    .setAttribute(GEN_AI_OPERATION_NAME, "agent")
                    .setAttribute(GEN_AI_AGENT_NAME, request.agentName());
            applyGatewayAttributes(rootBuilder, request.userId(), request.sessionId());
            root = rootBuilder.startSpan();
        }
        var spanBuilder = tracer.spanBuilder("gateway" + request.endpoint().path.replace('/', '.'))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(CLIENT_TYPE, "gateway")
                .setAttribute(LANGFUSE_OBSERVATION_TYPE, "generation")
                .setAttribute(GEN_AI_SYSTEM, call.provider().type)
                .setAttribute(GEN_AI_REQUEST_MODEL, call.upstreamModel())
                .setAttribute(LANGFUSE_INPUT, new String(request.body(), StandardCharsets.UTF_8));
        if (request.endpoint() == GatewayEndpointType.CHAT_COMPLETIONS || request.endpoint() == GatewayEndpointType.RESPONSES) {
            spanBuilder.setAttribute(GEN_AI_OPERATION_NAME, "chat");
        }
        applyGatewayAttributes(spanBuilder, request.userId(), request.sessionId());
        if (root != null) spanBuilder.setParent(Context.root().with(Span.wrap(root.getSpanContext())));
        var llm = spanBuilder.startSpan();
        return new GatewaySpanRoot(root != null ? root : llm, llm);
    }

    static void synthesizeToolSpans(TelemetryConfig telemetry, TraceRequest request, Span llmSpan) {
        if (!llmSpan.getSpanContext().isValid()) return;
        Map<String, Object> bodyMap;
        try {
            bodyMap = GatewayJson.MAPPER.readValue(request.body(), GatewaySupport.MAP_TYPE);
        } catch (IOException e) {
            LOGGER.debug("invalid request body, skipping tool span synthesis", e);
            return;
        }
        var toolCalls = GatewaySupport.parseToolCalls(bodyMap, GatewaySupport.MAX_SYNTHESIZED_TOOL_CALLS);
        if (toolCalls.isEmpty()) return;
        var tracer = telemetry.getOpenTelemetry().getTracer("core-ai-server");
        var parentContext = Context.root().with(Span.wrap(llmSpan.getSpanContext()));
        for (var toolCall : toolCalls) {
            var spanBuilder = tracer.spanBuilder(toolCall.name())
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(parentContext)
                    .setAttribute(CLIENT_TYPE, "gateway")
                    .setAttribute(LANGFUSE_OBSERVATION_TYPE, "tool")
                    .setAttribute(GEN_AI_OPERATION_NAME, "tool")
                    .setAttribute(TOOL_NAME, toolCall.name())
                    .setAttribute(TOOL_CALL_ID, toolCall.toolCallId());
            if (GatewaySupport.hasText(toolCall.arguments())) {
                spanBuilder.setAttribute(GEN_AI_PROMPT, GatewaySupport.truncate(toolCall.arguments(), MAX_TOOL_ARGUMENTS_LENGTH));
            }
            if (GatewaySupport.hasText(toolCall.content())) {
                spanBuilder.setAttribute(GEN_AI_COMPLETION, GatewaySupport.truncate(toolCall.content(), MAX_TOOL_RESULT_LENGTH));
            }
            applyGatewayAttributes(spanBuilder, request.userId(), request.sessionId());
            spanBuilder.startSpan().end();
        }
    }

    static void endSpans(GatewaySpanRoot spans) {
        spans.llm().end();
        if (!spans.root().getSpanContext().getSpanId().equals(spans.llm().getSpanContext().getSpanId())) {
            spans.root().end();
        }
    }

    private static void applyGatewayAttributes(SpanBuilder spanBuilder, String userId, String sessionId) {
        if (GatewaySupport.hasText(userId)) spanBuilder.setAttribute(USER_ID, userId);
        if (GatewaySupport.hasText(sessionId)) spanBuilder.setAttribute(SESSION_ID, sessionId);
    }

    private GatewayAgentTrace() {
    }

    record GatewaySpanRoot(Span root, Span llm) {
    }

    record TraceRequest(GatewayEndpointType endpoint, String userId, byte[] body, String sessionId, String agentName) {
    }
}
