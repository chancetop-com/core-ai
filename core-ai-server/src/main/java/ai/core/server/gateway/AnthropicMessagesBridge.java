package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.sse.RawSseChannel;
import core.framework.http.HTTPResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges the gateway's Anthropic messages surface onto OpenAI chat providers: it decides the
 * upstream transport, converts the request and response bodies and reports stream failures in the
 * client's protocol. Native anthropic providers are not bridged.
 */
final class AnthropicMessagesBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicMessagesBridge.class);

    static boolean isProvider(GatewayProviderConfig provider) {
        return "anthropic".equals(provider.type);
    }

    static boolean active(GatewayEndpointType endpoint, GatewayProviderConfig provider) {
        return endpoint == GatewayEndpointType.MESSAGES && !isProvider(provider);
    }

    static GatewayEndpointType upstreamEndpoint(GatewayProviderConfig provider, GatewayEndpointType endpoint) {
        if (endpoint == GatewayEndpointType.MESSAGES && !isProvider(provider)) return GatewayEndpointType.CHAT_COMPLETIONS;
        return endpoint;
    }

    static Map<String, Object> requestBody(Map<String, Object> requestBody, GatewayEndpointType endpoint,
                                           GatewayProviderConfig provider, GatewayEndpointType upstreamEndpoint,
                                           String upstreamModel, GatewayModelConfig modelConfig) {
        Map<String, Object> outgoingBody;
        if (active(endpoint, provider)) {
            outgoingBody = AnthropicMessagesProtocol.toChatRequest(requestBody, upstreamModel);
        } else {
            outgoingBody = new LinkedHashMap<>(requestBody);
            outgoingBody.put("model", upstreamModel);
        }
        GatewaySupport.mergeExtraBody(outgoingBody, provider.requestExtraBody);
        outgoingBody.put("model", upstreamModel);
        if (upstreamEndpoint == GatewayEndpointType.CHAT_COMPLETIONS || upstreamEndpoint == GatewayEndpointType.RESPONSES) {
            GatewayChatRequestNormalizer.normalize(outgoingBody, modelConfig);
        }
        return outgoingBody;
    }

    static byte[] responseBody(HTTPResponse upstream, String requestedModel) {
        var body = upstream.body;
        if (body == null || body.length == 0) {
            return upstream.statusCode >= 200 && upstream.statusCode < 300
                    ? json(AnthropicMessagesProtocol.toMessageResponse(Map.of(), requestedModel))
                    : body;
        }
        try {
            var parsed = GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE);
            if (upstream.statusCode >= 200 && upstream.statusCode < 300) {
                return json(AnthropicMessagesProtocol.toMessageResponse(parsed, requestedModel));
            }
            return json(AnthropicMessagesProtocol.toErrorResponse(upstream.statusCode, parsed));
        } catch (IOException e) {
            LOGGER.debug("upstream body is not a JSON chat response, forwarding it unchanged", e);
            return body;
        }
    }

    // Once the SSE response is committed as 200, surface the upstream failure as a protocol error
    // event instead of closing the stream silently.
    static void sendStreamError(RawSseChannel<?> channel, RuntimeException e) {
        try {
            var message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            var body = new LinkedHashMap<String, Object>();
            body.put("type", "error");
            body.put("error", Map.of("type", "api_error", "message", message));
            channel.sendRawEvent("error", GatewayJson.MAPPER.writeValueAsString(body));
        } catch (Exception ignored) {
            LOGGER.debug("failed to send gateway stream error event", ignored);
        }
    }

    private static byte[] json(Object value) {
        try {
            return GatewayJson.MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize anthropic gateway JSON", e);
        }
    }

    private AnthropicMessagesBridge() {
    }
}
