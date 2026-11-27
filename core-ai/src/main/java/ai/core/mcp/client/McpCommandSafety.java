package ai.core.mcp.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Security checks for desktop-managed MCP definitions. The CLI keeps its historical behaviour:
 * these rules apply only to the expanded desktop path.
 *
 * @author stephen
 */
public final class McpCommandSafety {
    private static final String CONTROL_CHARS = "\u0000\r\n";
    private static final String CMD_METACHARS = "&|<>^%!\"";
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    public static void validate(String serverName, Map<String, Object> config) {
        var command = config.get("command");
        if (command instanceof String text) {
            rejectCommandUnsafe(serverName, text);
        }
        if (config.get("args") instanceof List<?> args) {
            for (var arg : args) {
                rejectCommandUnsafe(serverName, String.valueOf(arg));
            }
        }
        validateHeaders(serverName, config.get("headers"));
    }

    private static void rejectCommandUnsafe(String serverName, String value) {
        if (containsAny(value, CONTROL_CHARS)) {
            throw new UnsafeCommandException("MCP server '" + serverName + "' contains control characters");
        }
        if (WINDOWS && containsAny(value, CMD_METACHARS)) {
            throw new UnsafeCommandException("MCP server '" + serverName
                    + "' contains characters that are unsafe in a Windows command wrapper");
        }
    }

    private static void validateHeaders(String serverName, Object headers) {
        if (!(headers instanceof Map<?, ?> map)) return;
        for (var entry : map.entrySet()) {
            var name = String.valueOf(entry.getKey());
            var value = String.valueOf(entry.getValue());
            if (containsAny(name, "\r\n") || containsAny(value, "\r\n")) {
                throw new UnsafeHeaderException("MCP server '" + serverName + "' has an invalid header");
            }
        }
    }

    private static boolean containsAny(String value, String characters) {
        for (int i = 0; i < value.length(); i++) {
            if (characters.indexOf(value.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private McpCommandSafety() {
    }

    /** Command or argument text that must not reach the Windows command wrapper. */
    public static final class UnsafeCommandException extends IllegalArgumentException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public UnsafeCommandException(String message) {
            super(message);
        }
    }

    /** A header name or value containing a line break. */
    public static final class UnsafeHeaderException extends IllegalArgumentException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public UnsafeHeaderException(String message) {
            super(message);
        }
    }
}
