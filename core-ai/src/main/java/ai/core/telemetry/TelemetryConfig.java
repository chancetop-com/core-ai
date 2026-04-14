package ai.core.telemetry;

import ai.core.telemetry.spi.LocalSpanProcessorProvider;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public final class TelemetryConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryConfig.class);

    public static Builder builder() {
        return new Builder();
    }

    private final String serviceName;
    private final String otlpEndpoint;
    private final boolean enabled;
    private final String serviceVersion;
    private final String environment;
    private final Map<String, String> headers;
    private final OpenTelemetry openTelemetry;

    private TelemetryConfig(Builder builder) {
        this.serviceName = builder.serviceName;
        this.enabled = builder.enabled;
        this.serviceVersion = builder.serviceVersion;
        this.environment = builder.environment;
        this.headers = new HashMap<>(builder.headers);
        this.otlpEndpoint = builder.otlpEndpoint;

        if (enabled) {
            this.openTelemetry = initializeOpenTelemetry();
        } else {
            this.openTelemetry = OpenTelemetry.noop();
        }
    }

    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private OpenTelemetry initializeOpenTelemetry() {
        try {
            var serviceNameKey = AttributeKey.stringKey("service.name");
            var serviceVersionKey = AttributeKey.stringKey("service.version");
            var deploymentEnvironmentKey = AttributeKey.stringKey("deployment.environment");

            var resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                    .put(serviceNameKey, serviceName)
                    .put(serviceVersionKey, serviceVersion)
                    .put(deploymentEnvironmentKey, environment).build()));

            var sdkTracerProviderBuilder = SdkTracerProvider.builder().setResource(resource);

            // Determine export mode based on endpoint configuration
            if (isLocalExportMode(otlpEndpoint)) {
                // Use local SpanProcessor to write directly to storage
                var localProcessor = loadLocalProcessor(serviceName, serviceVersion, environment);
                if (localProcessor != null) {
                    sdkTracerProviderBuilder.addSpanProcessor(localProcessor);
                    LOGGER.debug("OpenTelemetry initialized with local processor - service: {}", serviceName);
                } else {
                    LOGGER.warn("trace.otlp.endpoint is 'local' but no LocalSpanProcessorProvider found, tracing disabled");
                    return OpenTelemetry.noop();
                }
            } else {
                // Use HTTP/protobuf exporter for external OTLP endpoint (e.g., Langfuse)
                var spanExporterBuilder = OtlpHttpSpanExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .setTimeout(Duration.ofSeconds(10));

                // Add custom headers (e.g., for Langfuse Basic Auth)
                if (!headers.isEmpty()) {
                    headers.forEach(spanExporterBuilder::addHeader);
                }

                var spanExporter = spanExporterBuilder.build();

                sdkTracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                    .setScheduleDelay(1, TimeUnit.SECONDS)
                    .setMaxQueueSize(2048)
                    .setMaxExportBatchSize(512)
                    .setExporterTimeout(30, TimeUnit.SECONDS).build());

                LOGGER.debug("OpenTelemetry initialized with HTTP exporter - service: {}, endpoint: {}", serviceName, otlpEndpoint);
            }

            var sdkTracerProvider = sdkTracerProviderBuilder.build();

            var openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sdkTracerProvider.close();
                LOGGER.debug("OpenTelemetry tracer provider closed");
            }));

            return openTelemetrySdk;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize OpenTelemetry, falling back to noop", e);
            return OpenTelemetry.noop();
        }
    }

    private boolean isLocalExportMode(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return true;
        }
        var normalized = endpoint.trim().toLowerCase(Locale.getDefault());
        return "local".equals(normalized);
    }

    private SpanProcessor loadLocalProcessor(String serviceName, String serviceVersion, String environment) {
        var loader = ServiceLoader.load(LocalSpanProcessorProvider.class);
        for (var provider : loader) {
            var processor = provider.createLocalProcessor(serviceName, serviceVersion, environment);
            if (processor != null) {
                LOGGER.debug("Loaded LocalSpanProcessorProvider: {}", provider.getClass().getName());
                return processor;
            }
        }
        return null;
    }

    public static class Builder {
        private String serviceName = "core-ai";
        private String otlpEndpoint = null;  // null means local mode
        private boolean enabled = false;
        private String serviceVersion = "1.0.0";
        private String environment = "production";
        private final Map<String, String> headers = new HashMap<>();

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder otlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = serviceVersion;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder addHeader(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder addHeaders(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public TelemetryConfig build() {
            return new TelemetryConfig(this);
        }
    }
}
