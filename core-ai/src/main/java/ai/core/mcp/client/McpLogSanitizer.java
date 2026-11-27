package ai.core.mcp.client;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts known MCP secret values, authorization tokens, ANSI escape codes, and URL
 * userinfo/query segments from log lines and error summaries, and classifies MCP runtime
 * exceptions into fixed codes and safe messages without echoing raw exception messages.
 *
 * @author stephen
 */
public final class McpLogSanitizer {
    private static final int MIN_SECRET_LENGTH = 4;
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>)\\]]+");
    private static final Pattern AUTH_SCHEME_PATTERN = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[^\\s\"',;]+");
    private static final Pattern USERINFO_FALLBACK = Pattern.compile("(https?://)[^/@\\s]+@");
    private static final Set<String> REGISTERED_SECRETS = ConcurrentHashMap.newKeySet();

    public static void registerSecrets(Collection<String> values) {
        if (values == null) return;
        for (var value : values) {
            if (value != null && value.length() >= MIN_SECRET_LENGTH) {
                REGISTERED_SECRETS.add(value);
            }
        }
    }

    public static void clearSecrets() {
        REGISTERED_SECRETS.clear();
    }

    public static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) return "";
        var trimmed = url.trim();
        try {
            var uri = URI.create(trimmed);
            if (uri.getScheme() != null && uri.getHost() != null) {
                var sb = new StringBuilder();
                sb.append(uri.getScheme()).append("://").append(uri.getHost());
                if (uri.getPort() > 0) sb.append(':').append(uri.getPort());
                if (uri.getRawPath() != null) sb.append(uri.getRawPath());
                return sb.toString();
            }
        } catch (IllegalArgumentException ignored) {
            return fallbackStripUrl(trimmed);
        }
        return fallbackStripUrl(trimmed);
    }

    public static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) return "";
        var clean = ANSI_PATTERN.matcher(text).replaceAll("");
        clean = stripUrlsInText(clean);
        clean = AUTH_SCHEME_PATTERN.matcher(clean).replaceAll("$1 ***");
        if (!REGISTERED_SECRETS.isEmpty()) {
            var ordered = new ArrayList<>(REGISTERED_SECRETS);
            ordered.sort(Comparator.comparingInt(String::length).reversed());
            for (var secret : ordered) {
                if (clean.contains(secret)) {
                    clean = clean.replace(secret, "***");
                }
            }
        }
        return clean;
    }

    public static ErrorSummary classifyError(Throwable error) {
        if (error == null) return new ErrorSummary("MCP_PROBE_FAILED", "MCP request failed");
        var current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            var matched = classifySingle(current);
            if (matched != null) return matched;
            current = current.getCause();
            depth++;
        }
        return new ErrorSummary("MCP_PROBE_FAILED", "MCP probe failed");
    }

    private static ErrorSummary classifySingle(Throwable throwable) {
        if (throwable instanceof TimeoutException || throwable instanceof SocketTimeoutException) {
            return new ErrorSummary("MCP_PROBE_TIMEOUT", "MCP request timed out");
        }
        if (throwable instanceof NoSuchFileException) {
            return new ErrorSummary("MCP_COMMAND_NOT_FOUND", "MCP server command could not be started");
        }
        if (throwable instanceof ConnectException || throwable instanceof UnknownHostException
                || throwable instanceof NoRouteToHostException || throwable instanceof SocketException) {
            return new ErrorSummary("MCP_CONNECTION_FAILED", "MCP connection failed");
        }
        var message = throwable.getMessage();
        if (message == null) return null;
        var lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")
                || lower.contains("forbidden") || lower.contains("authentication failed")
                || lower.contains("auth failed")) {
            return new ErrorSummary("MCP_AUTH_FAILED", "MCP authentication failed");
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return new ErrorSummary("MCP_PROBE_TIMEOUT", "MCP request timed out");
        }
        if (lower.contains("cannot run program") || lower.contains("createprocess error=2")
                || lower.contains("no such file or directory")) {
            return new ErrorSummary("MCP_COMMAND_NOT_FOUND", "MCP server command could not be started");
        }
        if (lower.contains("connection refused") || lower.contains("unresolved")
                || lower.contains("connect failed")) {
            return new ErrorSummary("MCP_CONNECTION_FAILED", "MCP connection failed");
        }
        return null;
    }

    private static String stripUrlsInText(String text) {
        var matcher = URL_PATTERN.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(sanitizeUrl(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String fallbackStripUrl(String url) {
        var stripped = USERINFO_FALLBACK.matcher(url).replaceAll("$1");
        int query = stripped.indexOf('?');
        if (query >= 0) stripped = stripped.substring(0, query);
        int fragment = stripped.indexOf('#');
        if (fragment >= 0) stripped = stripped.substring(0, fragment);
        return stripped;
    }

    private McpLogSanitizer() {
    }

    public record ErrorSummary(String code, String message) {
    }
}
