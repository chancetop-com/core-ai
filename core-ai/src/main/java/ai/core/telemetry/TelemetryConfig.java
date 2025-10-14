package ai.core.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for OpenTelemetry tracing with OTLP export (compatible with Langfuse)
 *
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

        // Normalize endpoint: append Langfuse path if needed
        this.otlpEndpoint = normalizeEndpoint(builder.otlpEndpoint, builder.useLangfusePath);

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

    /**
     * Normalize OTLP endpoint URL for Langfuse compatibility
     * Appends /api/public/otel/v1/traces if not already present
     */
    private String normalizeEndpoint(String endpoint, boolean useLangfusePath) {
        if (!useLangfusePath || endpoint == null || endpoint.isEmpty()) {
            return endpoint;
        }

        // Remove trailing slash
        var normalizedEndpoint = endpoint.replaceAll("/+$", "");

        // Check if path is already included
        if (normalizedEndpoint.contains("/otel/") || normalizedEndpoint.endsWith("/v1/traces")) {
            return normalizedEndpoint;
        }

        // Append Langfuse self-hosted path
        return normalizedEndpoint + "/api/public/otel/v1/traces";
    }

    private OpenTelemetry initializeOpenTelemetry() {
        try {
            var serviceNameKey = AttributeKey.stringKey("service.name");
            var serviceVersionKey = AttributeKey.stringKey("service.version");
            var deploymentEnvironmentKey = AttributeKey.stringKey("deployment.environment");

            var resource = Resource.getDefault()
                .merge(Resource.create(Attributes.builder()
                    .put(serviceNameKey, serviceName)
                    .put(serviceVersionKey, serviceVersion)
                    .put(deploymentEnvironmentKey, environment)
                    .build()));

            // Use HTTP/protobuf exporter (required by Langfuse)
            var spanExporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofSeconds(10));

            // Add custom headers (e.g., for Langfuse Basic Auth)
            if (!headers.isEmpty()) {
                headers.forEach(spanExporterBuilder::addHeader);
            }

            var spanExporter = spanExporterBuilder.build();

            var sdkTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                    .setScheduleDelay(1, TimeUnit.SECONDS)
                    .setMaxQueueSize(2048)
                    .setMaxExportBatchSize(512)
                    .setExporterTimeout(30, TimeUnit.SECONDS)
                    .build())
                .setResource(resource)
                .build();

            var openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sdkTracerProvider.close();
                LOGGER.info("OpenTelemetry tracer provider closed");
            }));

            LOGGER.info("OpenTelemetry initialized - service: {}, endpoint: {}", serviceName, otlpEndpoint);
            return openTelemetrySdk;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize OpenTelemetry, falling back to noop", e);
            return OpenTelemetry.noop();
        }
    }

    public static class Builder {
        private String serviceName = "core-ai";
        private String otlpEndpoint = "http://localhost:4317";
        private boolean enabled = false;
        private String serviceVersion = "1.0.0";
        private String environment = "production";
        private final Map<String, String> headers = new HashMap<>();
        private boolean useLangfusePath = true;  // Default: append Langfuse path

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

        /**
         * Enable/disable automatic Langfuse path appending
         * When enabled, /api/public/otel/v1/traces will be appended to base URL
         * Default: true (enabled)
         */
        public Builder useLangfusePath(boolean useLangfusePath) {
            this.useLangfusePath = useLangfusePath;
            return this;
        }

        /**
         * Add a custom header for OTLP export (e.g., for authentication)
         */
        public Builder addHeader(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        /**
         * Add multiple custom headers for OTLP export
         */
        public Builder addHeaders(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public TelemetryConfig build() {
            return new TelemetryConfig(this);
        }
    }
}
