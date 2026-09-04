package ai.core.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes non-ASCII HTTP header values as RFC 2047 encoded-words ("=?UTF-8?B?...?=").
 * HTTP header values are ASCII-only — OkHttp rejects any char >= 0x7F when building a request,
 * which breaks headers carrying Chinese agent names. The LiteLLM provider encodes before sending
 * gateway headers (x-agent-name/x-session-id) and the gateway reader decodes; plain ASCII values
 * pass through unchanged so external clients that never encode keep working.
 */
public final class GatewayHeaderCodec {
    private static final String PREFIX = "=?UTF-8?B?";
    private static final String SUFFIX = "?=";

    public static String encode(String value) {
        if (isAscii(value)) return value;
        return PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + SUFFIX;
    }

    public static String decode(String value) {
        if (value.length() <= PREFIX.length() + SUFFIX.length() || !value.startsWith(PREFIX) || !value.endsWith(SUFFIX)) return value;
        try {
            var payload = value.substring(PREFIX.length(), value.length() - SUFFIX.length());
            return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;    // malformed payload — keep the plain value
        }
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private GatewayHeaderCodec() {
    }
}
