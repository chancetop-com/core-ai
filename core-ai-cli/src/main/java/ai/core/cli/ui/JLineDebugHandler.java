package ai.core.cli.ui;

import ai.core.cli.DebugLog;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * @author stephen
 */
final class JLineDebugHandler extends Handler {
    @Override
    public void publish(LogRecord record) {
        DebugLog.log("[jline] " + record.getMessage());
        if (record.getThrown() != null) {
            DebugLog.log("[jline] exception: " + record.getThrown());
        }
    }

    @Override
    public void flush() {
        // no buffering
    }

    @Override
    public void close() {
        // nothing to close
    }
}
