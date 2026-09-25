package ai.core.server.gateway;

import ai.core.api.server.gateway.TestGatewayProviderResponse;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;

import java.io.IOException;
import java.time.Duration;

import static ai.core.server.gateway.GatewaySupport.DEFAULT_TIMEOUT_SECONDS;
import static ai.core.server.gateway.GatewaySupport.stripTrailingSlash;
import static ai.core.server.gateway.GatewaySupport.truncate;
import static ai.core.server.gateway.GatewaySupport.valueOrDefault;

/**
 * Connectivity probes for the async media-task protocols, whose credentials cannot be verified through the
 * generic {@code /models} route: KIE has no model list at all, and Ark's video task list is the credential
 * surface the render path itself uses — the host's OpenAI-compatible {@code /models} needs the ListModels
 * permission group, which a key scoped to video generation may not carry. Neither probe starts a billable
 * task.
 *
 * @author stephen
 */
final class GatewayMediaProbe {
    private static final HTTPClient CLIENT = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(2))
            .build();

    static void test(GatewayProviderConfig provider, String apiKey, TestGatewayProviderResponse result) {
        var ark = "VOLCENGINE_ARK".equals(provider.mediaProtocol);
        var url = stripTrailingSlash(provider.baseUrl) + (ark ? "/contents/generations/tasks?page_size=1" : "/api/v1/chat/credit");
        GatewayNetworkGuard.validateOutboundUrl(url, Boolean.TRUE.equals(provider.allowPrivateNetwork));
        var request = new HTTPRequest(HTTPMethod.GET, url);
        request.headers.put("Content-Type", "application/json");
        request.connectTimeout = Duration.ofSeconds(valueOrDefault(provider.connectTimeoutSeconds, 10));
        request.timeout = Duration.ofSeconds(valueOrDefault(provider.timeoutSeconds, DEFAULT_TIMEOUT_SECONDS));
        GatewaySupport.applyAuth(provider, request, apiKey);
        var response = CLIENT.execute(request);
        result.ok = response.statusCode >= 200 && response.statusCode < 300;
        result.status = result.ok ? "ok" : "failed";
        result.message = result.ok ? connected(response, ark) : truncate("HTTP " + response.statusCode + ": " + response.text(), 300);
    }

    /** The probe body either carries a number worth reporting (KIE credit, Ark task count) or nothing. */
    private static String connected(HTTPResponse response, boolean ark) {
        try {
            var body = GatewayJson.MAPPER.readTree(response.body == null ? new byte[0] : response.body);
            var value = body.path(ark ? "total" : "data");
            return value.isNumber() ? "Connected, " + (ark ? "video tasks" : "credit") + "=" + value.asText() : "Connected";
        } catch (IOException e) {
            return "Connected";
        }
    }

    private GatewayMediaProbe() {
    }
}
