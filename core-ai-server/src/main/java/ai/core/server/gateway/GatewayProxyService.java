package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class GatewayProxyService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ContentType EVENT_STREAM = ContentType.create("text/event-stream", StandardCharsets.UTF_8);

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

    private Response proxy(byte[] body, GatewayEndpointType endpoint) {
        var requestBody = parseBody(body);
        var selection = routingEngine.route(string(requestBody.get("model")), endpoint);
        var provider = selection.provider();
        var outgoingBody = new LinkedHashMap<>(requestBody);
        outgoingBody.put("model", selection.upstreamModel());
        mergeExtraBody(outgoingBody, provider.requestExtraBody);

        var url = endpointUrl(provider, endpoint, selection.upstreamModel());
        GatewayNetworkGuard.validateOutboundUrl(url, Boolean.TRUE.equals(provider.allowPrivateNetwork));
        var upstreamRequest = new HTTPRequest(HTTPMethod.POST, url);
        upstreamRequest.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        applyAuth(provider, upstreamRequest);
        applyTimeouts(provider, upstreamRequest);
        upstreamRequest.body(writeJson(outgoingBody), ContentType.APPLICATION_JSON);

        if (Boolean.TRUE.equals(requestBody.get("stream"))) {
            return stream(upstreamRequest, provider);
        }
        return response(execute(upstreamRequest, provider));
    }

    private String endpointUrl(GatewayProviderConfig provider, GatewayEndpointType endpoint, String model) {
        var baseUrl = stripTrailingSlash(provider.baseUrl);
        if ("azure".equals(provider.type)) {
            var version = hasText(provider.apiVersion) ? provider.apiVersion : "2024-10-21";
            if (endpoint == GatewayEndpointType.CHAT_COMPLETIONS) {
                if (!hasText(model)) throw new BadRequestException("azure chat gateway requires a deployment model");
                return baseUrl + "/openai/deployments/" + urlEncode(model) + "/chat/completions?api-version=" + urlEncode(version);
            }
            return baseUrl + "/openai/responses?api-version=" + urlEncode(version);
        }
        return baseUrl + endpoint.path;
    }

    private void applyAuth(GatewayProviderConfig provider, HTTPRequest request) {
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        if (!hasText(apiKey)) return;
        if ("azure".equals(provider.type)) {
            request.headers.put("api-key", apiKey);
        } else {
            request.headers.put("Authorization", "Bearer " + apiKey);
        }
    }

    private void applyTimeouts(GatewayProviderConfig provider, HTTPRequest request) {
        if (provider.connectTimeoutSeconds != null) request.connectTimeout = Duration.ofSeconds(provider.connectTimeoutSeconds);
        if (provider.timeoutSeconds != null) request.timeout = Duration.ofSeconds(provider.timeoutSeconds);
    }

    private Response stream(HTTPRequest request, GatewayProviderConfig provider) {
        var builder = new StringBuilder();
        try (var source = sse(request, provider)) {
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
        return client(provider).execute(request);
    }

    EventSource sse(HTTPRequest request, GatewayProviderConfig provider) {
        return client(provider).sse(request);
    }

    private HTTPClient client(GatewayProviderConfig provider) {
        var builder = HTTPClient.builder();
        if (provider.connectTimeoutSeconds != null) builder.connectTimeout(Duration.ofSeconds(provider.connectTimeoutSeconds));
        if (provider.timeoutSeconds != null) builder.timeout(Duration.ofSeconds(provider.timeoutSeconds));
        return builder.build();
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

    private void mergeExtraBody(LinkedHashMap<String, Object> body, String extraBody) {
        if (!hasText(extraBody)) return;
        try {
            body.putAll(MAPPER.readValue(extraBody, MAP_TYPE));
        } catch (Exception e) {
            throw new BadRequestException("invalid provider extra body JSON: " + e.getMessage());
        }
    }

    private LinkedHashMap<String, Object> parseBody(byte[] body) {
        try {
            return MAPPER.readValue(body, MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage());
        }
    }

    private Response jsonResponse(Object data) {
        return Response.bytes(writeJson(data)).contentType(ContentType.APPLICATION_JSON);
    }

    private byte[] writeJson(Object data) {
        try {
            return MAPPER.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize gateway JSON", e);
        }
    }

    private String string(Object value) {
        return value instanceof String string ? string : null;
    }

    private String stripTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
