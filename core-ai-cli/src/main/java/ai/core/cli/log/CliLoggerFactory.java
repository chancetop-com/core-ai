package ai.core.cli.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public final class CliLoggerFactory implements ILoggerFactory {
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, CliLogger::new);
    }
}
