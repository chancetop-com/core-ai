package ai.core.cli;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * @author stephen
 */
@SuppressWarnings("PMD.SystemPrintln")
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
        System.err.println("[DEBUG " + LocalTime.now().format(TIME_FMT) + "] " + message);
        System.err.flush();
    }

    public static void log(String message, Throwable error) {
        if (!enabled) return;
        System.err.println("[DEBUG " + LocalTime.now().format(TIME_FMT) + "] " + message);
        error.printStackTrace(System.err);
        System.err.flush();
    }

    private DebugLog() {
    }
}
