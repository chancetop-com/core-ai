package ai.core.server.skill.telemetry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts credentials, tokens, database passwords, long base64 payloads, and local home paths
 * from skill telemetry and papercut text fields prior to persistence.
 *
 * @author stephen
 */
public final class SkillTelemetrySanitizer {
    public static final int MAX_SUMMARY_LENGTH = 200;
    public static final int MAX_OBSERVED_ERROR_LENGTH = 1000;
    public static final int MAX_SUGGESTED_FIX_LENGTH = 500;
    private static final String MASK = "***";

    private static final Pattern BEARER_HEADER_PATTERN = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)([^\\s\"',;]+)");
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\b(bearer\\s+)([A-Za-z0-9._~+/=-]{8,})");
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|client[_-]?secret|secret[_-]?key|password|passwd|token)\\s*([=:])\\s*(['\"]?)([^\\s'\",;&]{4,})\\3");
    private static final Pattern PREFIXED_TOKEN_PATTERN = Pattern.compile(
            "\\b(?:sk|ak|pk|rk|ghp|gho|ghu|ghs|glpat|xoxb|xoxp|xoxa|AIza)[-_]?[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern DB_URI_PASSWORD_PATTERN = Pattern.compile(
            "(?i)\\b((?:postgres|postgresql|mysql|mongodb(?:\\+srv)?|redis|rediss|amqp)://[^:@\\s/]+:)([^@\\s]+)(@)");
    private static final Pattern LONG_BASE64_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/]{40,}={0,2}(?![A-Za-z0-9+/_-])");
    private static final Pattern UNIX_HOME_PATTERN = Pattern.compile(
            "(?:/Users|/home)/[^/\\s\"':;]+");
    private static final Pattern WINDOWS_HOME_PATTERN = Pattern.compile(
            "(?i)[A-Z]:\\\\Users\\\\[^\\\\\\s\"':;]+");

    public static String sanitizeSummary(String input) {
        return sanitize(input, MAX_SUMMARY_LENGTH);
    }

    public static String sanitizeObservedError(String input) {
        return sanitize(input, MAX_OBSERVED_ERROR_LENGTH);
    }

    public static String sanitizeSuggestedFix(String input) {
        return sanitize(input, MAX_SUGGESTED_FIX_LENGTH);
    }

    public static String sanitize(String input, int maxLength) {
        if (input == null) return null;
        var trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        var masked = BEARER_HEADER_PATTERN.matcher(trimmed).replaceAll("$1" + MASK);
        masked = BEARER_TOKEN_PATTERN.matcher(masked).replaceAll("$1" + MASK);
        masked = DB_URI_PASSWORD_PATTERN.matcher(masked).replaceAll("$1" + MASK + "$3");
        masked = KEY_VALUE_SECRET_PATTERN.matcher(masked).replaceAll("$1$2" + MASK);
        masked = PREFIXED_TOKEN_PATTERN.matcher(masked).replaceAll(MASK);
        masked = JWT_PATTERN.matcher(masked).replaceAll(MASK);
        masked = maskLongBase64(masked);
        masked = UNIX_HOME_PATTERN.matcher(masked).replaceAll("~");
        masked = WINDOWS_HOME_PATTERN.matcher(masked).replaceAll("~");

        if (maxLength > 0 && masked.length() > maxLength) {
            return masked.substring(0, maxLength);
        }
        return masked;
    }

    private static String maskLongBase64(String input) {
        var matcher = LONG_BASE64_PATTERN.matcher(input);
        var sb = new StringBuilder(input.length());
        while (matcher.find()) {
            var token = matcher.group();
            matcher.appendReplacement(sb, looksLikeBase64Secret(token) ? MASK : Matcher.quoteReplacement(token));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean looksLikeBase64Secret(String token) {
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSymbol = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= 'A' && c <= 'Z') hasUpper = true;
            else if (c >= 'a' && c <= 'z') hasLower = true;
            else if (c >= '0' && c <= '9') hasDigit = true;
            else if (c == '+' || c == '/' || c == '=') hasSymbol = true;
        }
        int classes = (hasUpper ? 1 : 0) + (hasLower ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);
        return classes >= 3;
    }

    private SkillTelemetrySanitizer() {
    }
}
