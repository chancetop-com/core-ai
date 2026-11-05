package ai.core.server.gateway;

import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import ai.core.sse.RawSseChannel;
import ai.core.telemetry.TelemetryConfig;
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
import io.opentelemetry.api.trace.Span;
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

    public Response proxyMessages(byte[] body, String userId, String sessionId, String agentName) {
        return proxy(body, GatewayEndpointType.MESSAGES, MediaJobOwner.UNKNOWN, userId, sessionId, agentName);
    }

    /**
     * Anthropic's token counting surface. Native anthropic providers answer it themselves; for chat
     * providers the gateway estimates locally because the OpenAI protocol has no equivalent call.
     */
    public Response countMessageTokens(byte[] body) {
        var requestBody = parseBody(body);
        var selection = routingEngine.route(string(requestBody.get("model")), GatewayEndpointType.MESSAGES);
        var provider = selection.provider();
        if (!AnthropicMessagesBridge.isProvider(provider)) {
            return jsonResponse(Map.of("input_tokens", AnthropicTokenEstimator.estimate(requestBody)));
        }
        var outgoingBody = new LinkedHashMap<>(requestBody);
        outgoingBody.put("model", selection.upstreamModel());
        var url = GatewaySupport.anthropicApiBaseUrl(provider) + GatewayEndpointType.MESSAGES.path + "/count_tokens";
        GatewayNetworkGuard.validateOutboundUrl(url, Boolean.TRUE.equals(provider.allowPrivateNetwork));
        var upstreamRequest = new HTTPRequest(HTTPMethod.POST, url);
        upstreamRequest.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        GatewaySupport.applyAuth(provider, upstreamRequest, apiKey(provider));
        applyTimeouts(provider, upstreamRequest);
        upstreamRequest.body(writeJson(outgoingBody), ContentType.APPLICATION_JSON);
        return response(execute(upstreamRequest, provider));
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
        var traceRequest = new GatewayAgentTrace.TraceRequest(endpoint, userId, body, sessionId, agentName);
        var spans = GatewayAgentTrace.startSpans(telemetryConfig, call, traceRequest);
        var span = spans.llm();
        GatewayAgentTrace.synthesizeToolSpans(telemetryConfig, traceRequest, span);
        var bridge = AnthropicMessagesBridge.active(endpoint, call.provider());
        try {
            if (!call.stream()) {
                var upstream = execute(call.request(), call.provider());
                GatewayProxyTrace.recordUsage(span, upstream.body);
                var outbound = bridge ? AnthropicMessagesBridge.responseBody(upstream, call.requestedModel()) : upstream.body;
                var outboundText = new String(outbound == null ? new byte[0] : outbound, StandardCharsets.UTF_8);
                GatewayProxyTrace.output(span, outboundText);
                GatewayProxyTrace.markUpstreamStatus(span, upstream.statusCode);
                if (bridge && upstream.statusCode >= 400) {
                    // the SSE route is already committed as 200; report the failure with the protocol's
                    // own error event so the Anthropic client can parse it
                    channel.sendRawEvent("error", outboundText);
                } else {
                    channel.sendRawData(outboundText);
                }
                return;
            }
            if (bridge) {
                streamAnthropicEvents(call, channel, span);
            } else {
                streamEvents(call, channel, span);
            }
            span.setStatus(StatusCode.OK);
        } catch (RuntimeException e) {
            if (bridge) AnthropicMessagesBridge.sendStreamError(channel, e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            spans.root().setStatus(StatusCode.ERROR, e.getMessage());
            LOGGER.error("gateway {} upstream request failed, uri={}", endpoint.path, call.request().uri, e);
            throw e;
        } finally {
            GatewayAgentTrace.endSpans(spans);
        }
    }

    private void streamAnthropicEvents(GatewayUpstreamCall call, RawSseChannel<?> channel, Span span) {
        var translator = new AnthropicMessagesStream(call.requestedModel());
        var output = new StringBuilder();
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                GatewayProxyTrace.recordStreamUsage(span, event.data());
                GatewayProxyTrace.appendStreamOutput(output, event.data());
                for (var translated : translator.onEvent(event.type(), event.data())) {
                    channel.sendRawEvent(translated.event(), translated.data());
                }
                if ("[DONE]".equals(event.data())) break;
            }
        }
        for (var translated : translator.finish()) {
            channel.sendRawEvent(translated.event(), translated.data());
        }
        GatewayProxyTrace.output(span, output.toString());
    }

    private void streamEvents(GatewayUpstreamCall call, RawSseChannel<?> channel, Span span) {
        var output = new StringBuilder();
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                channel.sendRawEvent(event.type(), event.data());
                GatewayProxyTrace.recordStreamUsage(span, event.data());
                GatewayProxyTrace.appendStreamOutput(output, event.data());
                if ("[DONE]".equals(event.data())) break;
            }
        }
        GatewayProxyTrace.output(span, output.toString());
    }

    private Response proxy(byte[] body, GatewayEndpointType endpoint, MediaJobOwner owner, String userId, String sessionId, String agentName) {
        var call = prepare(body, endpoint);
        if (endpoint == GatewayEndpointType.VIDEO_GENERATION && call.stream()) {
            throw new BadRequestException("streaming video generation is not supported by the gateway");
        }
        var bridge = AnthropicMessagesBridge.active(endpoint, call.provider());
        var traceRequest = new GatewayAgentTrace.TraceRequest(endpoint, userId, body, sessionId, agentName);
        var spans = GatewayAgentTrace.startSpans(telemetryConfig, call, traceRequest);
        var span = spans.llm();
        GatewayAgentTrace.synthesizeToolSpans(telemetryConfig, traceRequest, span);
        try {
            if (call.stream()) {
                var response = bufferedStream(call, span, endpoint);
                span.setStatus(StatusCode.OK);
                return response;
            }
            var upstream = execute(call.request(), call.provider());
            GatewayProxyTrace.recordUsage(span, upstream.body);
            var outbound = bridge ? AnthropicMessagesBridge.responseBody(upstream, call.requestedModel()) : upstream.body;
            GatewayProxyTrace.output(span, new String(outbound == null ? new byte[0] : outbound, StandardCharsets.UTF_8));
            GatewayProxyTrace.markUpstreamStatus(span, upstream.statusCode);
            if (endpoint == GatewayEndpointType.VIDEO_GENERATION && upstream.statusCode >= 200 && upstream.statusCode < 300) {
                return gatewayVideoResponse(upstream, call, owner, videoSeconds(body), videoPrompt(body));
            }
            if (bridge) return jsonResponse(outbound, upstream.statusCode);
            return response(upstream);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            spans.root().setStatus(StatusCode.ERROR, e.getMessage());
            LOGGER.error("gateway {} upstream request failed, uri={}", endpoint.path, call.request().uri, e);
            throw e;
        } finally {
            GatewayAgentTrace.endSpans(spans);
        }
    }

    private GatewayUpstreamCall prepare(byte[] body, GatewayEndpointType endpoint) {
        var requestBody = parseBody(body);
        var requestedModel = string(requestBody.get("model"));
        var selection = routingEngine.route(requestedModel, endpoint);
        var provider = selection.provider();
        var upstreamEndpoint = AnthropicMessagesBridge.upstreamEndpoint(provider, endpoint);
        var outgoingBody = AnthropicMessagesBridge.requestBody(requestBody, endpoint, provider, upstreamEndpoint,
                selection.upstreamModel(), routingEngine.modelConfig(requestedModel));

        var url = endpointUrl(provider, upstreamEndpoint, selection.upstreamModel());
        GatewayNetworkGuard.validateOutboundUrl(url, Boolean.TRUE.equals(provider.allowPrivateNetwork));
        var upstreamRequest = new HTTPRequest(HTTPMethod.POST, url);
        upstreamRequest.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        GatewaySupport.applyAuth(provider, upstreamRequest, apiKey(provider));
        applyTimeouts(provider, upstreamRequest);
        upstreamRequest.body(writeJson(outgoingBody), ContentType.APPLICATION_JSON);
        return new GatewayUpstreamCall(upstreamRequest, provider, requestedModel != null ? requestedModel : selection.upstreamModel(),
                selection.upstreamModel(), Boolean.TRUE.equals(requestBody.get("stream")));
    }

    private String endpointUrl(GatewayProviderConfig provider, GatewayEndpointType endpoint, String model) {
        if (endpoint == GatewayEndpointType.MESSAGES) {
            if (!AnthropicMessagesBridge.isProvider(provider)) {
                throw new BadRequestException("messages endpoint requires an anthropic provider");
            }
            return GatewaySupport.anthropicApiBaseUrl(provider) + endpoint.path;
        }
        var baseUrl = stripTrailingSlash(provider.baseUrl);
        if ("kie".equals(provider.type)
                && (endpoint == GatewayEndpointType.CHAT_COMPLETIONS || endpoint == GatewayEndpointType.RESPONSES)) {
            return GatewaySupport.kieChatBaseUrl(provider) + endpoint.path;
        }
        if (AnthropicMessagesBridge.isProvider(provider)) return GatewaySupport.anthropicApiBaseUrl(provider) + endpoint.path;
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

    private Response bufferedStream(GatewayUpstreamCall call, Span span, GatewayEndpointType endpoint) {
        var builder = new StringBuilder();
        var output = new StringBuilder();
        var translator = AnthropicMessagesBridge.active(endpoint, call.provider())
                ? new AnthropicMessagesStream(call.requestedModel()) : null;
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                GatewayProxyTrace.recordStreamUsage(span, event.data());
                GatewayProxyTrace.appendStreamOutput(output, event.data());
                if (translator == null) {
                    appendEvent(builder, event.id(), event.type(), event.data());
                } else {
                    for (var translated : translator.onEvent(event.type(), event.data())) {
                        appendEvent(builder, null, translated.event(), translated.data());
                    }
                }
                if ("[DONE]".equals(event.data())) break;
            }
        }
        if (translator != null) {
            for (var translated : translator.finish()) {
                appendEvent(builder, null, translated.event(), translated.data());
            }
        }
        GatewayProxyTrace.output(span, output.toString());
        return Response.bytes(builder.toString().getBytes(StandardCharsets.UTF_8)).contentType(EVENT_STREAM);
    }

    private void appendEvent(StringBuilder builder, String id, String type, String data) {
        if (hasText(id)) builder.append("id: ").append(id).append('\n');
        if (hasText(type)) builder.append("event: ").append(type).append('\n');
        if (data != null) {
            for (var line : data.split("\\R", -1)) {
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

    private Response gatewayVideoResponse(HTTPResponse upstream, GatewayUpstreamCall call, MediaJobOwner owner,
                                          Integer seconds, String prompt) {
        var body = parseBody(upstream.body == null ? new byte[0] : upstream.body);
        var upstreamVideoId = string(body.get("id"));
        if (!hasText(upstreamVideoId)) throw new BadRequestException("upstream video response is missing id");
        var route = new GatewayRoute(call.provider(), call.upstreamModel());
        // raw passthrough: the body is forwarded verbatim, so the gateway never learns the references it carries
        var submission = new MediaJobService.VideoJobSubmission(upstreamVideoId, null, seconds, prompt, null, null);
        var job = mediaJobService.createVideoJob(owner, route, call.requestedModel(), submission);
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

    private String videoPrompt(byte[] requestBody) {
        try {
            return string(parseBody(requestBody).get("prompt"));
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

    private Response jsonResponse(byte[] body, int statusCode) {
        var response = body == null || body.length == 0 ? Response.empty() : Response.bytes(body);
        response.status(status(statusCode));
        response.contentType(ContentType.APPLICATION_JSON);
        return response;
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
