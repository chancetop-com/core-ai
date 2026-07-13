package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import ai.core.sse.RawSseChannel;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static ai.core.server.gateway.GatewaySupport.hasText;
import static ai.core.server.gateway.GatewaySupport.stripTrailingSlash;
import static ai.core.server.gateway.GatewaySupport.urlEncode;
import static ai.core.server.gateway.GatewaySupport.valueOrDefault;

public class GatewayProxyService {
    private static final ContentType EVENT_STREAM = ContentType.create("text/event-stream", StandardCharsets.UTF_8);
    // shared client with a high ceiling; effective limits come from per-request timeouts set in applyTimeouts
    private static final HTTPClient CLIENT = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(10))
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewaySecretProtector secretProtector;

    public Response proxyChatCompletions(byte[] body) {
        return proxy(body, GatewayEndpointType.CHAT_COMPLETIONS);
    }

    public Response proxyResponses(byte[] body) {
        return proxy(body, GatewayEndpointType.RESPONSES);
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

    void streamToChannel(byte[] body, GatewayEndpointType endpoint, RawSseChannel<?> channel) {
        var call = prepare(body, endpoint);
        if (!call.stream()) {
            var upstream = execute(call.request(), call.provider());
            channel.sendRawData(new String(upstream.body == null ? new byte[0] : upstream.body, StandardCharsets.UTF_8));
            return;
        }
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                channel.sendRawEvent(event.type(), event.data());
                if ("[DONE]".equals(event.data())) break;
            }
        }
    }

    private Response proxy(byte[] body, GatewayEndpointType endpoint) {
        var call = prepare(body, endpoint);
        if (call.stream()) return bufferedStream(call);
        return response(execute(call.request(), call.provider()));
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
        return new GatewayUpstreamCall(upstreamRequest, provider, Boolean.TRUE.equals(requestBody.get("stream")));
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
        request.timeout = Duration.ofSeconds(valueOrDefault(provider.timeoutSeconds, 30));
    }

    private Response bufferedStream(GatewayUpstreamCall call) {
        var builder = new StringBuilder();
        try (var source = sse(call.request(), call.provider())) {
            for (var event : source) {
                appendEvent(builder, event);
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

    private String string(Object value) {
        return value instanceof String string ? string : null;
    }
}
