package ai.core.cli;

import ai.core.cli.log.CliLogger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * @author stephen
 */
public final class DebugLog {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile boolean enabled;

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void log(String message) {
        if (!enabled) return;
        var line = "[DEBUG " + LocalTime.now().format(TIME_FMT) + "] " + message;
        CliLogger.writeToFileDirect(line, null);
    }

    public static void log(String message, Throwable error) {
        if (!enabled) return;
        var line = "[DEBUG " + LocalTime.now().format(TIME_FMT) + "] " + message;
        CliLogger.writeToFileDirect(line, error);
    }

    private DebugLog() {
    }
}
