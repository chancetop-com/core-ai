package ai.core.server.trace.spi;

import ai.core.telemetry.spi.LocalSpanProcessorProvider;

import io.opentelemetry.sdk.trace.SpanProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class LocalSpanProcessorProviderImpl implements LocalSpanProcessorProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSpanProcessorProviderImpl.class);

    @Override
    public SpanProcessor createLocalProcessor(String serviceName, String serviceVersion, String environment) {
        LOGGER.debug("Creating local SpanProcessor for service: {}, version: {}, environment: {}",
                serviceName, serviceVersion, environment);
        return new LocalSpanProcessor(serviceName, serviceVersion, environment);
    }
}
