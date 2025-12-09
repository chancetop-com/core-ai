package ai.core.memory.util;

/**
 * Utility methods for memory operations.
 *
 * @author xander
 */
public final class MemoryUtils {
    private static final int DEFAULT_TRUNCATE_LENGTH = 50;

    /**
     * Clean JSON content by removing markdown code block wrappers.
     *
     * @param content raw JSON content potentially wrapped in markdown
     * @return cleaned JSON string
     */
    public static String cleanJson(String content) {
        if (content == null) {
            return "{}";
        }
        String result = content.trim();
        if (result.startsWith("```json")) {
            result = result.substring(7);
        } else if (result.startsWith("```")) {
            result = result.substring(3);
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.length() - 3);
        }
        return result.trim();
    }

    /**
     * Truncate a string to the specified maximum length.
     *
     * @param s      the string to truncate
     * @param maxLen maximum length
     * @return truncated string with ellipsis if necessary
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Truncate a string to default length (50 characters).
     *
     * @param s the string to truncate
     * @return truncated string with ellipsis if necessary
     */
    public static String truncate(String s) {
        return truncate(s, DEFAULT_TRUNCATE_LENGTH);
    }

    /**
     * Escape special characters for JSON string value.
     *
     * @param s the string to escape
     * @return escaped string safe for JSON
     */
    public static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private MemoryUtils() {
        // Utility class
    }
}
