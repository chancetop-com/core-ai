package ai.core.server.gateway;

import ai.core.telemetry.TelemetryConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Global OpenTelemetry can only be registered once per JVM, so every span test shares this single instance.
 * A second build logs an error and, worse, the spans exported from the config that lost the registration
 * are silently dropped - which surfaces as a random "span export timed out" in an unrelated test class.
 *
 * @author stephen
 */
@SuppressFBWarnings("EI_EXPOSE_STATIC_REP2")
final class SpanTestTelemetry {
    static final TelemetryConfig INSTANCE = TelemetryConfig.builder().enabled(true).build();

    private SpanTestTelemetry() {
    }
}
