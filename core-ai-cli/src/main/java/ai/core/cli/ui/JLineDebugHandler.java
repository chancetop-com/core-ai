package ai.core.cli.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * @author stephen
 */
final class JLineDebugHandler extends Handler {
    private static final Logger logger = LoggerFactory.getLogger(JLineDebugHandler.class);

    @Override
    public void publish(LogRecord record) {
        logger.debug("[jline] {}", record.getMessage());
        if (record.getThrown() != null) {
            logger.debug("[jline] exception: {}", record.getThrown());
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
