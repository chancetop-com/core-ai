package ai.core.session;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Text and path helpers for background-task terminal notifications. Kept out of the manager so the
 * task accounting stays readable.
 *
 * @author stephen
 */
final class TaskNotificationText {
    private static final int MAX_NOTIFICATION_RESULT_LENGTH = 30 * 1024;
    private static final int MAX_TERMINAL_RESULT_LENGTH = 200 * 1024;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    static String terminalResult(String result, String outputRef) {
        if (result == null || result.length() <= MAX_TERMINAL_RESULT_LENGTH) return result;
        return result.substring(0, MAX_TERMINAL_RESULT_LENGTH)
                + "\n\n[Output truncated: full result is available at: " + (outputRef != null ? outputRef : "(not available)") + "]";
    }

    static boolean samePath(String left, String right) {
        var normalizedLeft = normalizePath(left);
        var normalizedRight = normalizePath(right);
        return IS_WINDOWS ? normalizedLeft.equalsIgnoreCase(normalizedRight) : normalizedLeft.equals(normalizedRight);
    }

    static String buildNotificationXml(String taskId, String status, String outputRef, String result, String error,
                                       long generation) {
        var resultXml = "completed".equals(status)
                ? "<result>" + truncateResult(result, outputRef) + "</result>"
                : "<error>" + truncateResult(error, outputRef) + "</error>";
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        return "<task-notification>%n<task-id>%s</task-id>%n<task-generation>%d</task-generation>%n<status>%s</status>%n%s%s%n</task-notification>%n"
                .formatted(taskId, generation, status, outputRefXml, resultXml);
    }

    private static String normalizePath(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return path;
        }
    }

    private static String truncateResult(String text, String outputRef) {
        if (text == null || text.length() <= MAX_NOTIFICATION_RESULT_LENGTH) return text;
        var truncated = text.substring(0, MAX_NOTIFICATION_RESULT_LENGTH);
        var suffix = "\n\n[Output truncated: showing first " + MAX_NOTIFICATION_RESULT_LENGTH
                + " characters. Full output is available at: " + outputRef + "]";
        return truncated + suffix;
    }

    private TaskNotificationText() {
    }
}
