package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import core.framework.http.HTTPRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class GatewaySupport {
    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String trimToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String stripTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    static long valueOrDefault(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static void applyAuth(GatewayProviderConfig provider, HTTPRequest request, String apiKey) {
        if (isBlank(apiKey)) return;
        if ("azure".equals(provider.type)) {
            request.headers.put("api-key", apiKey);
        } else {
            request.headers.put("Authorization", "Bearer " + apiKey);
        }
    }

    private GatewaySupport() {
    }
}
