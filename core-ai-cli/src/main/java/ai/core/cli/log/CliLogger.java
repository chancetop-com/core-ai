package ai.core.cli.log;

import ai.core.cli.DebugLog;
import org.slf4j.Marker;
import org.slf4j.helpers.AbstractLogger;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * @author stephen
 */
public final class CliLogger extends AbstractLogger {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final PrintStream STDERR = System.err;

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

        STDERR.println(line);
        if (throwable != null) {
            throwable.printStackTrace(STDERR);
        }
        STDERR.flush();
    }

    private boolean isMuted() {
        return name.startsWith("io.modelcontextprotocol.");
    }

    private boolean isLevelEnabled(org.slf4j.event.Level level) {
        if (isMuted()) return DebugLog.isEnabled();
        return switch (level) {
            case TRACE -> false;
            case DEBUG, INFO -> DebugLog.isEnabled();
            case WARN, ERROR -> true;
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
        return DebugLog.isEnabled();
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return DebugLog.isEnabled();
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
