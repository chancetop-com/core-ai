package ai.core.server.gateway;

import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import ai.core.sse.RawSseChannel;
import ai.core.telemetry.TelemetryConfig;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
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

    // OpenAI chat/completions uses prompt_tokens/completion_tokens, responses uses input_tokens/output_tokens
    static Usage parseUsage(Map<String, Object> body) {
        if (body == null || !(body.get("usage") instanceof Map<?, ?> usage)) return null;
        long input = number(usage.get("prompt_tokens"), usage.get("input_tokens"));
        long output = number(usage.get("completion_tokens"), usage.get("output_tokens"));
        if (input == 0 && output == 0) return null;
        Object cached = usage.get("cached_tokens");
        if (cached == null) cached = detailValue(usage, "prompt_tokens_details");
        if (cached == null) cached = detailValue(usage, "input_tokens_details");
        return new Usage(input, output, number(cached, null));
    }

    private static long number(Object primary, Object fallback) {
        if (primary instanceof Number value) return value.longValue();
        if (fallback instanceof Number value) return value.longValue();
        return 0;
    }

    private static Object detailValue(Map<?, ?> usage, String detailsKey) {
        return usage.get(detailsKey) instanceof Map<?, ?> details ? details.get("cached_tokens") : null;
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewaySecretProtector secretProtector;
    @Inject
    MediaJobService mediaJobService;
    @Inject
    TelemetryConfig telemetryConfig;

    public Response proxyChatCompletions(byte[] body, String userId) {
        return proxy(body, GatewayEndpointType.CHAT_COMPLETIONS, MediaJobOwner.UNKNOWN, userId);
    }

    public Response proxyResponses(byte[] body, String userId) {
        return proxy(body, GatewayEndpointType.RESPONSES, MediaJobOwner.UNKNOWN, userId);
    }

    public Response proxyImageGenerations(byte[] body, String userId) {
        return proxy(body, GatewayEndpointType.IMAGE_GENERATION, MediaJobOwner.UNKNOWN, userId);
    }

    public Response proxyImageEdits(byte[] body, String userId) {
        return proxy(body, GatewayEndpointType.IMAGE_EDIT, MediaJobOwner.UNKNOWN, userId);
    }

    public Response proxyVideoGenerations(byte[] body, MediaJobOwner owner) {
        return proxy(body, GatewayEndpointType.VIDEO_GENERATION, owner, owner.userId());
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

    void streamToChannel(byte[] body, GatewayEndpointType endpoint, RawSseChannel<?> channel, String userId) {
        var call = prepare(body, endpoint);
        var span = startSpan(call, endpoint, userId, body);
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
            throw e;
        } finally {
            span.end();
        }
    }

    private void streamEvents(GatewayUpstreamCall call, RawSseChannel<?> channel, Span span) {
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                channel.sendRawEvent(event.type(), event.data());
                recordStreamUsage(span, event.data());
                if ("[DONE]".equals(event.data())) break;
            }
        }
    }

    private Response proxy(byte[] body, GatewayEndpointType endpoint, MediaJobOwner owner, String userId) {
        var call = prepare(body, endpoint);
        if (endpoint == GatewayEndpointType.VIDEO_GENERATION && call.stream()) {
            throw new BadRequestException("streaming video generation is not supported by the gateway");
        }
        var span = startSpan(call, endpoint, userId, body);
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
                return gatewayVideoResponse(upstream, call, owner);
            }
            return response(upstream);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private Span startSpan(GatewayUpstreamCall call, GatewayEndpointType endpoint, String userId, byte[] body) {
        var telemetry = telemetryConfig;
        if (telemetry == null || !telemetry.isEnabled()) {
            return OpenTelemetry.noop().getTracer("core-ai-server").spanBuilder("gateway").startSpan();
        }
        var spanBuilder = telemetry.getOpenTelemetry().getTracer("core-ai-server")
                .spanBuilder("gateway" + endpoint.path.replace('/', '.'))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(LANGFUSE_OBSERVATION_TYPE, "generation")
                .setAttribute(GEN_AI_SYSTEM, call.provider().type)
                .setAttribute(GEN_AI_REQUEST_MODEL, call.upstreamModel())
                .setAttribute(LANGFUSE_INPUT, new String(body, StandardCharsets.UTF_8));
        if (endpoint == GatewayEndpointType.CHAT_COMPLETIONS || endpoint == GatewayEndpointType.RESPONSES) {
            spanBuilder.setAttribute(GEN_AI_OPERATION_NAME, "chat");
        }
        if (userId != null && !userId.isBlank()) {
            spanBuilder.setAttribute(USER_ID, userId);
        }
        return spanBuilder.startSpan();
    }

    private void markUpstreamStatus(Span span, int statusCode) {
        if (statusCode >= 400) span.setStatus(StatusCode.ERROR, "upstream returned " + statusCode);
        else span.setStatus(StatusCode.OK);
    }

    private void recordUsage(Span span, byte[] body) {
        if (body == null || body.length == 0) return;
        try {
            applyUsage(span, parseUsage(GatewayJson.MAPPER.readValue(body, MAP_TYPE)));
        } catch (Exception e) {
            LOGGER.debug("response body is not JSON with usage, usage stays unknown", e);
        }
    }

    private void recordStreamUsage(Span span, String data) {
        if (data == null || !data.contains("usage")) return;
        try {
            applyUsage(span, parseUsage(GatewayJson.MAPPER.readValue(data, MAP_TYPE)));
        } catch (Exception e) {
            LOGGER.debug("stream chunk is not a JSON usage payload", e);
        }
    }

    private void applyUsage(Span span, Usage usage) {
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
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                appendEvent(builder, event);
                recordStreamUsage(span, event.data());
                if ("[DONE]".equals(event.data())) break;
            }
        }
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

    private Response gatewayVideoResponse(HTTPResponse upstream, GatewayUpstreamCall call, MediaJobOwner owner) {
        var body = parseBody(upstream.body == null ? new byte[0] : upstream.body);
        var upstreamVideoId = string(body.get("id"));
        if (!hasText(upstreamVideoId)) throw new BadRequestException("upstream video response is missing id");
        var route = new GatewayRoute(call.provider(), call.upstreamModel());
        var job = mediaJobService.createVideoJob(owner, route, call.requestedModel(), upstreamVideoId);
        body.put("id", GatewayVideoHandle.encode(job.id));
        var response = Response.bytes(writeJson(body));
        response.status(status(upstream.statusCode));
        response.contentType(ContentType.APPLICATION_JSON);
        return response;
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
            body.putAll(GatewayJson.MAPPER.readValue(extraBody, MAP_TYPE));
        } catch (Exception e) {
            throw new BadRequestException("invalid provider extra body JSON: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private Map<String, Object> parseBody(byte[] body) {
        try {
            return GatewayJson.MAPPER.readValue(body, MAP_TYPE);
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

    record Usage(long inputTokens, long outputTokens, long cachedTokens) {
    }
}
