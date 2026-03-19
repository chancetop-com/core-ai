package ai.core.cli.log;

import ai.core.cli.DebugLog;
import org.slf4j.Marker;
import org.slf4j.helpers.AbstractLogger;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * @author stephen
 */
public final class CliLogger extends AbstractLogger {
    @Serial
    private static final long serialVersionUID = 7591457691679122691L;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final PrintStream STDERR = System.err;
    private static final Path LOGS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "logs");

    private static volatile PrintWriter fileWriter;
    private static volatile String currentSessionId = "default";

    public static void initialize(String sessionId) {
        currentSessionId = sessionId;
        try {
            Files.createDirectories(LOGS_DIR);
        } catch (IOException e) {
            STDERR.println("Failed to create logs directory: " + e.getMessage());
        }
        closeFileWriter();
        Path logFile = LOGS_DIR.resolve(sessionId + ".log");
        try {
            var bw = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            fileWriter = new PrintWriter(bw, true);
        } catch (IOException e) {
            STDERR.println("Failed to open log file: " + e.getMessage());
            fileWriter = null;
        }
    }

    public static void close() {
        closeFileWriter();
    }

    private static void closeFileWriter() {
        PrintWriter writer = fileWriter;
        if (writer != null) {
            synchronized (CliLogger.class) {
                if (fileWriter != null) {
                    fileWriter.close();
                    fileWriter = null;
                }
            }
        }
    }

    private static void writeToFile(String line, Throwable throwable) {
        PrintWriter writer = fileWriter;
        if (writer != null) {
            synchronized (CliLogger.class) {
                writer.println(line);
                if (throwable != null) {
                    throwable.printStackTrace(writer);
                }
            }
        }
    }

    public static void writeToFileDirect(String line, Throwable throwable) {
        writeToFile(line, throwable);
    }

    public static String getCurrentSessionId() {
        return currentSessionId;
    }

    public static void setCurrentSessionId(String currentSessionId) {
        CliLogger.currentSessionId = currentSessionId;
    }

    CliLogger(String name) {
        this.name = name;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(org.slf4j.event.Level level, Marker marker, String message, Object[] arguments, Throwable throwable) {
        if (!isLevelEnabled(level)) return;

        var formatted = formatMessage(message, arguments);
        var timestamp = LocalTime.now().format(TIME_FMT);
        var line = "[" + level.name() + " " + timestamp + "] " + name + " - " + formatted;

        // INFO/DEBUG write to file only; WARN/ERROR from core-ai packages write to both file and terminal
        boolean writeToTerminal = (level == org.slf4j.event.Level.WARN || level == org.slf4j.event.Level.ERROR)
                && isCoreAiLogger();

        writeToFile(line, throwable);

        if (writeToTerminal) {
            STDERR.println(line);
            if (throwable != null) {
                throwable.printStackTrace(STDERR);
            }
            STDERR.flush();
        }
    }

    private boolean isMuted() {
        return name.startsWith("io.modelcontextprotocol.");
    }

    private boolean isCoreAiLogger() {
        return name.startsWith("ai.core.");
    }

    private boolean isLevelEnabled(org.slf4j.event.Level level) {
        if (isMuted()) return DebugLog.isEnabled();
        return switch (level) {
            case TRACE -> false;
            case DEBUG -> DebugLog.isEnabled();
            case INFO, WARN, ERROR -> true;
        };
    }

    @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
    private String formatMessage(String message, Object[] arguments) {
        if (arguments == null || arguments.length == 0) return message;
        var sb = new StringBuilder(message.length() + 64);
        int argIndex = 0;
        int start = 0;
        while (argIndex < arguments.length) {
            int pos = message.indexOf("{}", start);
            if (pos < 0) break;
            sb.append(message, start, pos);
            sb.append(arguments[argIndex++]);
            start = pos + 2;
        }
        sb.append(message, start, message.length());
        return sb.toString();
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return false;
    }

    @Override
    public boolean isDebugEnabled() {
        return DebugLog.isEnabled();
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return DebugLog.isEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return true;  // INFO: always enabled for file logging
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return true;  // INFO: always enabled for file logging
    }

    @Override
    public boolean isWarnEnabled() {
        return !isMuted() || DebugLog.isEnabled();
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return !isMuted() || DebugLog.isEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
        return !isMuted() || DebugLog.isEnabled();
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return !isMuted() || DebugLog.isEnabled();
    }
}
