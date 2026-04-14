package ai.core.telemetry.spi;

import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * @author stephen
 */
public interface LocalSpanProcessorProvider {
    SpanProcessor createLocalProcessor(String serviceName, String serviceVersion, String environment);
}
