package ai.core.server.gateway;

import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import ai.core.sse.RawSseChannel;
import ai.core.telemetry.TelemetryConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.http.EventSource;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.inject.Inject;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.core.server.gateway.GatewaySupport.DEFAULT_TIMEOUT_SECONDS;
import static ai.core.server.gateway.GatewaySupport.hasText;
import static ai.core.server.gateway.GatewaySupport.stripTrailingSlash;
import static ai.core.server.gateway.GatewaySupport.urlEncode;
import static ai.core.server.gateway.GatewaySupport.valueOrDefault;

public class GatewayProxyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayProxyService.class);
    private static final ContentType EVENT_STREAM = ContentType.create("text/event-stream", StandardCharsets.UTF_8);
    // shared client with a high ceiling; effective limits come from per-request timeouts set in applyTimeouts
    private static final HTTPClient CLIENT = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(10))
            .build();
    private static final AttributeKey<String> LANGFUSE_OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    private static final AttributeKey<String> LANGFUSE_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> LANGFUSE_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_CACHED_TOKENS = AttributeKey.longKey("gen_ai.usage.cached_tokens");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> CLIENT_TYPE = AttributeKey.stringKey("client.type");
    private static final AttributeKey<String> GEN_AI_AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("tool.name");
    private static final AttributeKey<String> GEN_AI_PROMPT = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> GEN_AI_COMPLETION = AttributeKey.stringKey("gen_ai.completion");
    // cap the span output so a very long streaming response cannot inflate the stored span
    private static final int MAX_SPAN_OUTPUT_LENGTH = 200_000;
    private static final int MAX_TOOL_ARGUMENTS_LENGTH = 4000;
    private static final int MAX_TOOL_RESULT_LENGTH = 8000;

    // Root span (agent when the client sends x-agent-name, otherwise the LLM span itself) plus the
    // LLM span; tool spans synthesized from the request messages are children of the LLM span.
    record GatewaySpanRoot(Span root, Span llm) {
    }

    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewaySecretProtector secretProtector;
    @Inject
    MediaJobService mediaJobService;
    @Inject
    TelemetryConfig telemetryConfig;

    public Response proxyChatCompletions(byte[] body, String userId, String sessionId, String agentName) {
        return proxy(body, GatewayEndpointType.CHAT_COMPLETIONS, MediaJobOwner.UNKNOWN, userId, sessionId, agentName);
    }

    public Response proxyResponses(byte[] body, String userId, String sessionId, String agentName) {
        return proxy(body, GatewayEndpointType.RESPONSES, MediaJobOwner.UNKNOWN, userId, sessionId, agentName);
    }

    public Response proxyImageGenerations(byte[] body, String userId) {
        return proxy(body, GatewayEndpointType.IMAGE_GENERATION, MediaJobOwner.UNKNOWN, userId, null, null);
    }

    public Response proxyImageEdits(byte[] body, String userId) {
        return proxy(body, GatewayEndpointType.IMAGE_EDIT, MediaJobOwner.UNKNOWN, userId, null, null);
    }

    public Response proxyVideoGenerations(byte[] body, MediaJobOwner owner) {
        return proxy(body, GatewayEndpointType.VIDEO_GENERATION, owner, owner.userId(), null, null);
    }

    public Response getVideoStatus(String videoId, String userId) {
        return proxyVideoGet(videoId, userId, false);
    }

    public Response downloadVideoContent(String videoId, String userId) {
        return proxyVideoGet(videoId, userId, true);
    }

    private Response proxyVideoGet(String videoId, String userId, boolean content) {
        var job = mediaJobService.getOwned(GatewayVideoHandle.decode(videoId), userId);
        var provider = routingEngine.jobProvider(job.providerId);
        var suffix = content ? "/content" : "";
        var url = stripTrailingSlash(provider.baseUrl) + "/videos/" + urlEncode(job.upstreamVideoId) + suffix;
        GatewayNetworkGuard.validateOutboundUrl(url, Boolean.TRUE.equals(provider.allowPrivateNetwork));
        var request = new HTTPRequest(HTTPMethod.GET, url);
        request.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        GatewaySupport.applyAuth(provider, request, apiKey(provider));
        applyTimeouts(provider, request);
        var upstream = execute(request, provider);
        if (!content && upstream.statusCode >= 200 && upstream.statusCode < 300) {
            updateVideoJobStatus(job, upstream);
        }
        return response(upstream);
    }


    public Response models() {
        var data = routingEngine.models().stream().map(model -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", model.id());
            row.put("object", "model");
            row.put("owned_by", model.ownedBy());
            return row;
        }).toList();
        var response = new LinkedHashMap<String, Object>();
        response.put("object", "list");
        response.put("data", data);
        return jsonResponse(response);
    }

    void streamToChannel(byte[] body, GatewayEndpointType endpoint, RawSseChannel<?> channel, String userId, String sessionId, String agentName) {
        var call = prepare(body, endpoint);
        var spans = startSpans(call, endpoint, userId, body, sessionId, agentName);
        var span = spans.llm();
        synthesizeToolSpans(body, span, userId, sessionId);
        try {
            if (!call.stream()) {
                var upstream = execute(call.request(), call.provider());
                recordUsage(span, upstream.body);
                span.setAttribute(LANGFUSE_OUTPUT, new String(upstream.body == null ? new byte[0] : upstream.body, StandardCharsets.UTF_8));
                markUpstreamStatus(span, upstream.statusCode);
                channel.sendRawData(new String(upstream.body == null ? new byte[0] : upstream.body, StandardCharsets.UTF_8));
                return;
            }
            streamEvents(call, channel, span);
            span.setStatus(StatusCode.OK);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            spans.root().setStatus(StatusCode.ERROR, e.getMessage());
            LOGGER.error("gateway {} upstream request failed, uri={}", endpoint.path, call.request().uri, e);
            throw e;
        } finally {
            endSpans(spans, span);
        }
    }

    private void streamEvents(GatewayUpstreamCall call, RawSseChannel<?> channel, Span span) {
        var output = new StringBuilder();
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                channel.sendRawEvent(event.type(), event.data());
                recordStreamUsage(span, event.data());
                appendStreamOutput(output, event.data());
                if ("[DONE]".equals(event.data())) break;
            }
        }
        if (output.length() > 0) span.setAttribute(LANGFUSE_OUTPUT, output.toString());
    }

    private Response proxy(byte[] body, GatewayEndpointType endpoint, MediaJobOwner owner, String userId, String sessionId, String agentName) {
        var call = prepare(body, endpoint);
        if (endpoint == GatewayEndpointType.VIDEO_GENERATION && call.stream()) {
            throw new BadRequestException("streaming video generation is not supported by the gateway");
        }
        var spans = startSpans(call, endpoint, userId, body, sessionId, agentName);
        var span = spans.llm();
        synthesizeToolSpans(body, span, userId, sessionId);
        try {
            if (call.stream()) {
                var response = bufferedStream(call, span);
                span.setStatus(StatusCode.OK);
                return response;
            }
            var upstream = execute(call.request(), call.provider());
            recordUsage(span, upstream.body);
            span.setAttribute(LANGFUSE_OUTPUT, new String(upstream.body == null ? new byte[0] : upstream.body, StandardCharsets.UTF_8));
            markUpstreamStatus(span, upstream.statusCode);
            if (endpoint == GatewayEndpointType.VIDEO_GENERATION && upstream.statusCode >= 200 && upstream.statusCode < 300) {
                return gatewayVideoResponse(upstream, call, owner, videoSeconds(body));
            }
            return response(upstream);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            spans.root().setStatus(StatusCode.ERROR, e.getMessage());
            LOGGER.error("gateway {} upstream request failed, uri={}", endpoint.path, call.request().uri, e);
            throw e;
        } finally {
            endSpans(spans, span);
        }
    }

    private void endSpans(GatewaySpanRoot spans, Span llm) {
        llm.end();
        if (!spans.root().getSpanContext().getSpanId().equals(llm.getSpanContext().getSpanId())) {
            spans.root().end();
        }
    }

    private GatewaySpanRoot startSpans(GatewayUpstreamCall call, GatewayEndpointType endpoint, String userId, byte[] body, String sessionId, String agentName) {
        var telemetry = telemetryConfig;
        if (telemetry == null || !telemetry.isEnabled()) {
            var noop = OpenTelemetry.noop().getTracer("core-ai-server").spanBuilder("gateway").startSpan();
            return new GatewaySpanRoot(noop, noop);
        }
        var tracer = telemetry.getOpenTelemetry().getTracer("core-ai-server");
        Span root = null;
        if (hasText(agentName)) {
            // synthesize an agent layer above the LLM span, mirroring the agent/llm/tool hierarchy of server-side chats
            var rootBuilder = tracer.spanBuilder(agentName)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute(CLIENT_TYPE, "gateway")
                    .setAttribute(LANGFUSE_OBSERVATION_TYPE, "agent")
                    .setAttribute(GEN_AI_OPERATION_NAME, "agent")
                    .setAttribute(GEN_AI_AGENT_NAME, agentName);
            applyGatewayAttributes(rootBuilder, userId, sessionId);
            root = rootBuilder.startSpan();
        }
        var spanBuilder = tracer.spanBuilder("gateway" + endpoint.path.replace('/', '.'))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(CLIENT_TYPE, "gateway")
                .setAttribute(LANGFUSE_OBSERVATION_TYPE, "generation")
                .setAttribute(GEN_AI_SYSTEM, call.provider().type)
                .setAttribute(GEN_AI_REQUEST_MODEL, call.upstreamModel())
                .setAttribute(LANGFUSE_INPUT, new String(body, StandardCharsets.UTF_8));
        if (endpoint == GatewayEndpointType.CHAT_COMPLETIONS || endpoint == GatewayEndpointType.RESPONSES) {
            spanBuilder.setAttribute(GEN_AI_OPERATION_NAME, "chat");
        }
        applyGatewayAttributes(spanBuilder, userId, sessionId);
        if (root != null) {
            spanBuilder.setParent(Context.root().with(Span.wrap(root.getSpanContext())));
        }
        var llm = spanBuilder.startSpan();
        return new GatewaySpanRoot(root != null ? root : llm, llm);
    }

    private void applyGatewayAttributes(io.opentelemetry.api.trace.SpanBuilder spanBuilder, String userId, String sessionId) {
        if (userId != null && !userId.isBlank()) {
            spanBuilder.setAttribute(USER_ID, userId);
        }
        if (hasText(sessionId)) {
            // session id is recorded so the ingest layer can merge all requests of one client
            // conversation (e.g. Claude Code's X-Claude-Code-Session-Id) into a single trace
            spanBuilder.setAttribute(SESSION_ID, sessionId);
        }
    }

    // Client-side tool executions (framework or MCP) return to the LLM as tool messages in the next
    // request; synthesize them as tool spans under the LLM span so the trace shows a tool layer
    // without any extra client reporting.
    private void synthesizeToolSpans(byte[] body, Span llmSpan, String userId, String sessionId) {
        if (!llmSpan.getSpanContext().isValid()) return;
        Map<String, Object> bodyMap;
        try {
            bodyMap = parseBody(body);
        } catch (BadRequestException e) {
            return;  // prepare() already validated the body; nothing to synthesize on malformed input
        }
        var toolCalls = GatewaySupport.parseToolCalls(bodyMap, GatewaySupport.MAX_SYNTHESIZED_TOOL_CALLS);
        if (toolCalls.isEmpty()) return;
        var tracer = telemetryConfig.getOpenTelemetry().getTracer("core-ai-server");
        var parentContext = Context.root().with(Span.wrap(llmSpan.getSpanContext()));
        for (var toolCall : toolCalls) {
            var spanBuilder = tracer.spanBuilder(toolCall.name())
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(parentContext)
                    .setAttribute(CLIENT_TYPE, "gateway")
                    .setAttribute(LANGFUSE_OBSERVATION_TYPE, "tool")
                    .setAttribute(GEN_AI_OPERATION_NAME, "tool")
                    .setAttribute(TOOL_NAME, toolCall.name());
            if (hasText(toolCall.arguments())) {
                spanBuilder.setAttribute(GEN_AI_PROMPT, GatewaySupport.truncate(toolCall.arguments(), MAX_TOOL_ARGUMENTS_LENGTH));
            }
            if (hasText(toolCall.content())) {
                spanBuilder.setAttribute(GEN_AI_COMPLETION, GatewaySupport.truncate(toolCall.content(), MAX_TOOL_RESULT_LENGTH));
            }
            applyGatewayAttributes(spanBuilder, userId, sessionId);
            spanBuilder.startSpan().end();
        }
    }

    private void markUpstreamStatus(Span span, int statusCode) {
        if (statusCode >= 400) span.setStatus(StatusCode.ERROR, "upstream returned " + statusCode);
        else span.setStatus(StatusCode.OK);
    }

    private void recordUsage(Span span, byte[] body) {
        if (body == null || body.length == 0) return;
        try {
            applyUsage(span, GatewaySupport.parseUsage(GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE)));
        } catch (Exception e) {
            LOGGER.debug("response body is not JSON with usage, usage stays unknown", e);
        }
    }

    private void recordStreamUsage(Span span, String data) {
        if (data == null || !data.contains("usage")) return;
        try {
            applyUsage(span, GatewaySupport.parseUsage(GatewayJson.MAPPER.readValue(data, GatewaySupport.MAP_TYPE)));
        } catch (Exception e) {
            LOGGER.debug("stream chunk is not a JSON usage payload", e);
        }
    }

    private void applyUsage(Span span, GatewayUsage usage) {
        if (usage == null) return;
        span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, usage.inputTokens());
        span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, usage.outputTokens());
        if (usage.cachedTokens() > 0) span.setAttribute(GEN_AI_USAGE_CACHED_TOKENS, usage.cachedTokens());
    }

    private GatewayUpstreamCall prepare(byte[] body, GatewayEndpointType endpoint) {
        var requestBody = parseBody(body);
        var selection = routingEngine.route(string(requestBody.get("model")), endpoint);
        var provider = selection.provider();
        var outgoingBody = new LinkedHashMap<>(requestBody);
        mergeExtraBody(outgoingBody, provider.requestExtraBody);
        outgoingBody.put("model", selection.upstreamModel());
        if (endpoint == GatewayEndpointType.CHAT_COMPLETIONS) {
            var model = routingEngine.modelConfig(string(requestBody.get("model")));
            GatewayChatRequestNormalizer.normalize(outgoingBody, model == null ? null : model.supportsReasoningEffort);
        }

        var url = endpointUrl(provider, endpoint, selection.upstreamModel());
        GatewayNetworkGuard.validateOutboundUrl(url, Boolean.TRUE.equals(provider.allowPrivateNetwork));
        var upstreamRequest = new HTTPRequest(HTTPMethod.POST, url);
        upstreamRequest.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        GatewaySupport.applyAuth(provider, upstreamRequest, apiKey(provider));
        applyTimeouts(provider, upstreamRequest);
        upstreamRequest.body(writeJson(outgoingBody), ContentType.APPLICATION_JSON);
        return new GatewayUpstreamCall(upstreamRequest, provider, string(requestBody.get("model")),
                selection.upstreamModel(), Boolean.TRUE.equals(requestBody.get("stream")));
    }

    private String endpointUrl(GatewayProviderConfig provider, GatewayEndpointType endpoint, String model) {
        var baseUrl = stripTrailingSlash(provider.baseUrl);
        if (!"azure".equals(provider.type)) return baseUrl + endpoint.path;
        // tolerate a base url that already includes the openai api path (e.g. https://xxx.openai.azure.com/openai/v1)
        if (baseUrl.endsWith("/openai/v1")) baseUrl = baseUrl.substring(0, baseUrl.length() - "/openai/v1".length());
        var version = hasText(provider.apiVersion) ? provider.apiVersion : "2024-10-21";
        if (endpoint != GatewayEndpointType.CHAT_COMPLETIONS) {
            return baseUrl + "/openai/responses?api-version=" + urlEncode(version);
        }
        if (!hasText(model)) throw new BadRequestException("azure chat gateway requires a deployment model");
        return baseUrl + "/openai/deployments/" + urlEncode(model) + "/chat/completions?api-version=" + urlEncode(version);
    }

    private String apiKey(GatewayProviderConfig provider) {
        return secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
    }

    private void applyTimeouts(GatewayProviderConfig provider, HTTPRequest request) {
        request.connectTimeout = Duration.ofSeconds(valueOrDefault(provider.connectTimeoutSeconds, 10));
        request.timeout = Duration.ofSeconds(valueOrDefault(provider.timeoutSeconds, DEFAULT_TIMEOUT_SECONDS));
    }

    private Response bufferedStream(GatewayUpstreamCall call, Span span) {
        var builder = new StringBuilder();
        var output = new StringBuilder();
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                appendEvent(builder, event);
                recordStreamUsage(span, event.data());
                appendStreamOutput(output, event.data());
                if ("[DONE]".equals(event.data())) break;
            }
        }
        if (output.length() > 0) span.setAttribute(LANGFUSE_OUTPUT, output.toString());
        return Response.bytes(builder.toString().getBytes(StandardCharsets.UTF_8)).contentType(EVENT_STREAM);
    }

    private void appendEvent(StringBuilder builder, EventSource.Event event) {
        if (hasText(event.id())) builder.append("id: ").append(event.id()).append('\n');
        if (hasText(event.type())) builder.append("event: ").append(event.type()).append('\n');
        if (event.data() != null) {
            for (var line : event.data().split("\\R", -1)) {
                builder.append("data: ").append(line).append('\n');
            }
        }
        builder.append('\n');
    }

    // Streaming chat chunks carry the assistant text in choices[].delta.content (or choices[].message.content);
    // concatenate them so the span output holds the final assistant response instead of nothing.
    private void appendStreamOutput(StringBuilder output, String data) {
        if (data == null || "[DONE]".equals(data) || output.length() >= MAX_SPAN_OUTPUT_LENGTH || !data.contains("content")) return;
        try {
            var body = GatewayJson.MAPPER.readValue(data, GatewaySupport.MAP_TYPE);
            var choices = body.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) return;
            var choice = list.getFirst();
            if (!(choice instanceof Map<?, ?> choiceMap)) return;
            var content = contentText(choiceMap.get("delta"));
            if (content == null) content = contentText(choiceMap.get("message"));
            if (content != null && !content.isBlank()) {
                output.append(content);
            }
        } catch (JsonProcessingException e) {
            // non-JSON chunk (e.g. keepalive), nothing to record
            LOGGER.debug("stream chunk is not a JSON payload, skipped from span output", e);
        }
    }

    private String contentText(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        return map.get("content") instanceof String text ? text : null;
    }

    HTTPResponse execute(HTTPRequest request, GatewayProviderConfig provider) {
        return CLIENT.execute(request);
    }

    EventSource sse(HTTPRequest request, GatewayProviderConfig provider) {
        return CLIENT.sse(request);
    }

    private void updateVideoJobStatus(MediaJob job, HTTPResponse upstream) {
        var body = parseBody(upstream.body == null ? new byte[0] : upstream.body);
        mediaJobService.updateVideoStatus(job, new VideoStatusResponse(
                string(body.get("id")), string(body.get("status")), integer(body.get("progress")), string(body.get("error")), null));
    }

    private Response gatewayVideoResponse(HTTPResponse upstream, GatewayUpstreamCall call, MediaJobOwner owner, Integer seconds) {
        var body = parseBody(upstream.body == null ? new byte[0] : upstream.body);
        var upstreamVideoId = string(body.get("id"));
        if (!hasText(upstreamVideoId)) throw new BadRequestException("upstream video response is missing id");
        var route = new GatewayRoute(call.provider(), call.upstreamModel());
        var job = mediaJobService.createVideoJob(owner, route, call.requestedModel(), upstreamVideoId, null, seconds);
        body.put("id", GatewayVideoHandle.encode(job.id));
        var response = Response.bytes(writeJson(body));
        response.status(status(upstream.statusCode));
        response.contentType(ContentType.APPLICATION_JSON);
        return response;
    }

    private Integer videoSeconds(byte[] requestBody) {
        try {
            var value = parseBody(requestBody).get("seconds");
            return value instanceof Number number ? number.intValue() : null;
        } catch (BadRequestException e) {
            return null;
        }
    }

    private Response response(HTTPResponse upstream) {
        var body = upstream.body == null ? new byte[0] : upstream.body;
        var response = body.length == 0 ? Response.empty() : Response.bytes(body);
        response.status(status(upstream.statusCode));
        response.contentType(upstream.contentType != null ? upstream.contentType : ContentType.APPLICATION_JSON);
        return response;
    }

    private HTTPStatus status(int code) {
        for (var status : HTTPStatus.values()) {
            if (status.code == code) return status;
        }
        return code >= 200 && code < 300 ? HTTPStatus.OK : HTTPStatus.BAD_GATEWAY;
    }

    private void mergeExtraBody(Map<String, Object> body, String extraBody) {
        if (!hasText(extraBody)) return;
        try {
            body.putAll(GatewayJson.MAPPER.readValue(extraBody, GatewaySupport.MAP_TYPE));
        } catch (Exception e) {
            throw new BadRequestException("invalid provider extra body JSON: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private Map<String, Object> parseBody(byte[] body) {
        try {
            return GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private Response jsonResponse(Object data) {
        return Response.bytes(writeJson(data)).contentType(ContentType.APPLICATION_JSON);
    }

    private byte[] writeJson(Object data) {
        try {
            return GatewayJson.MAPPER.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize gateway JSON", e);
        }
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String string(Object value) {
        return value instanceof String string ? string : null;
    }
}
