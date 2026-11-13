package ai.core.server.share;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes structured credentials from shared execution payloads, including JSON nested in strings.
 *
 * @author Xander
 */
public final class SessionShareSanitizer {
    private static final String REDACTED = "[redacted]";
    private static final int MAX_DEPTH = 48;
    private static final Set<String> SECRET_KEYS = Set.of("authorization", "proxyauthorization", "cookie", "setcookie",
        "apikey", "xapikey", "accesstoken", "refreshtoken", "idtoken", "password", "passwd", "secret",
        "clientsecret", "token", "credential", "credentials", "privatekey", "accountkey", "subscriptionkey",
        "ocpapimsubscriptionkey", "xauthtoken", "xamzsecuritytoken", "xamzsignature", "signature", "sig");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)(\\b(?:authorization|proxy-authorization|cookie|set-cookie|(?:x[-_])?api[-_]?key|access[-_]?token|"
            + "refresh[-_]?token|client[-_]?secret|password|secret|x-amz-security-token|x-amz-signature|signature|sig)\\b"
            + "[\\\"']?\\s*[:=]\\s*)(?:\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"|'[^']*'|[^\\r\\n,;&}]+)");
    private static final Pattern URL_SECRET = Pattern.compile(
        "(?i)([?&](?:api[-_]?key|access[-_]?token|refresh[-_]?token|token|password|secret|sig|signature|"
            + "x-amz-signature|x-amz-security-token|x-goog-signature|key)=)[^&#\\s\\\"'<>]*");
    private static final Pattern URL_USER_INFO = Pattern.compile("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]{8,}");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)(\\b(?:Cookie|Set-Cookie)\\s*:\\s*)[^\\r\\n]*");

    public static String sanitize(String value) {
        return sanitize(value, 0);
    }

    private static String sanitize(String value, int depth) {
        if (value == null || value.isEmpty()) return value;
        if (depth > MAX_DEPTH) return REDACTED;
        var stripped = value.stripLeading();
        if (stripped.startsWith("{") || stripped.startsWith("[")) {
            try {
                var node = JsonUtil.OBJECT_MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
                return JsonUtil.OBJECT_MAPPER.writeValueAsString(sanitizeNode(node, depth + 1));
            } catch (JsonProcessingException ignored) {
                return sanitizeText(value);
            }
        }
        return sanitizeText(value);
    }

    private static JsonNode sanitizeNode(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) return TextNode.valueOf(REDACTED);
        if (node instanceof ObjectNode object) {
            var result = object.objectNode();
            var name = object.has("name") ? object.get("name") : object.get("key");
            boolean namedSecret = name != null && name.isTextual() && secret(name.textValue());
            object.properties().forEach(entry -> result.set(entry.getKey(), secret(entry.getKey())
                || namedSecret && ("value".equals(entry.getKey()) || "values".equals(entry.getKey()))
                ? TextNode.valueOf(REDACTED) : sanitizeNode(entry.getValue(), depth + 1)));
            return result;
        }
        if (node instanceof ArrayNode array) {
            var result = array.arrayNode();
            if (array.size() == 2 && array.get(0).isTextual() && secret(array.get(0).textValue())) {
                return result.add(array.get(0)).add(REDACTED);
            }
            array.forEach(value -> result.add(sanitizeNode(value, depth + 1)));
            return result;
        }
        if (node.isTextual()) return TextNode.valueOf(sanitize(node.textValue(), depth + 1));
        return node.deepCopy();
    }

    private static boolean secret(String key) {
        var normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SECRET_KEYS.contains(normalized) || normalized.endsWith("apikey") || normalized.endsWith("password")
            || normalized.endsWith("clientsecret") || normalized.endsWith("accesstoken")
            || normalized.endsWith("refreshtoken") || normalized.endsWith("authorization")
            || normalized.endsWith("cookie");
    }

    private static String sanitizeText(String text) {
        var result = COOKIE_HEADER.matcher(text).replaceAll("$1[redacted]");
        result = URL_SECRET.matcher(result).replaceAll("$1%5Bredacted%5D");
        result = URL_USER_INFO.matcher(result).replaceAll("$1[redacted]@");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1[redacted]");
        return BEARER.matcher(result).replaceAll("$1 [redacted]");
    }

    private SessionShareSanitizer() {
    }
}
