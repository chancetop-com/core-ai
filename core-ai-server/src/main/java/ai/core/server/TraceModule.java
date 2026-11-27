package ai.core.server;

import ai.core.api.server.prompt.PromptWebService;
import ai.core.api.server.trace.TraceWebService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.pg.StoreMode;
import ai.core.server.task.TaskRunner;
import ai.core.server.trace.maintenance.TraceArchiveService;
import ai.core.server.trace.maintenance.TraceArchivingJob;
import ai.core.server.trace.maintenance.TraceArchivingTask;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceJob;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceService;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceTask;
import ai.core.server.trace.maintenance.TracePayloadBackfillJob;
import ai.core.server.trace.maintenance.TracePayloadBackfillTask;
import ai.core.server.trace.maintenance.StaleRunCleanupJob;
import ai.core.server.trace.maintenance.StaleRunCleanupService;
import ai.core.server.trace.service.IngestService;
import ai.core.server.trace.service.MemoryTraceSpanBuffer;
import ai.core.server.trace.service.ModelPricingService;
import ai.core.server.trace.service.OTLPIngestService;
import ai.core.server.trace.service.MongoPromptTemplateStore;
import ai.core.server.trace.service.PgPromptTemplateStore;
import ai.core.server.trace.service.PromptService;
import ai.core.server.trace.service.PromptTemplateStore;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceBodyStore;
import ai.core.server.trace.service.TracePayloadStore;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.service.TraceVisibilityResolver;
import ai.core.server.trace.spi.LocalSpanProcessorRegistry;
import ai.core.server.trace.web.ingest.IngestController;
import ai.core.server.trace.web.otlp.OTLPController;
import ai.core.server.trace.web.prompt.PromptWebServiceImpl;
import ai.core.server.trace.web.TraceBodyController;
import ai.core.server.trace.web.TracePayloadController;
import ai.core.server.trace.web.trace.TraceWebServiceImpl;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * @author stephen
 */
public class TraceModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceModule.class);

    @Override
    protected void initialize() {
        var archivePrefix = resolveArchivePrefix();
        var configuredContainer = property("trace.archive.container").orElse(null);
        var payloadContainer = property("sys.trace.payload.container").orElse(null);
        var payloadStorageContainer = payloadContainer == null || payloadContainer.isBlank() ? configuredContainer : payloadContainer;
        var tracePostgres = StoreMode.postgres(property("sys.trace.store.mode").orElse("auto"),
            property("sys.store.mode").orElse("mongo"), "sys.trace.store.mode");
        var bodyStore = bind(TraceBodyStore.class);
        bodyStore.configure(property("sys.trace.body.offload.enabled").orElse("auto"),
            longProperty("sys.trace.body.inline.max.bytes", 16L * 1024),
            payloadStorageContainer, archivePrefix, tracePostgres);
        var payloadStore = bind(TracePayloadStore.class);
        payloadStore.configure(property("sys.trace.payload.storage").orElse("auto"),
            payloadStorageContainer, archivePrefix, tracePostgres);
        var backfillMode = property("sys.trace.payload.backfill.enabled").orElse("auto");
        var backfillEnabled = "true".equals(backfillMode) || "auto".equals(backfillMode) && tracePostgres;
        payloadStore.configureBackfill(backfillEnabled,
            intProperty("sys.trace.payload.backfill.batch.size", 500));
        bind(TraceDailyMaintenanceService.class);
        var archiveService = new TraceArchiveService(bean(ObjectStorageServiceResolver.class), configuredContainer, archivePrefix);
        bind(archiveService);
        var traceDailyMaintenanceTask = bind(TraceDailyMaintenanceTask.class);
        var taskRunner = bean(TaskRunner.class);
        onStartup(() -> taskRunner.register(traceDailyMaintenanceTask));
        schedule().fixedRate("trace-daily-maintenance", bind(TraceDailyMaintenanceJob.class), Duration.ofHours(1));
        var traceArchivingTask = bind(TraceArchivingTask.class);
        onStartup(() -> taskRunner.register(traceArchivingTask));
        schedule().fixedRate("trace-archive", bind(TraceArchivingJob.class), Duration.ofMinutes(TraceArchivingJob.TICK_MINUTES));
        var backfillTask = bind(TracePayloadBackfillTask.class);
        onStartup(() -> taskRunner.register(backfillTask));
        if (backfillEnabled) {
            schedule().fixedRate("trace-payload-backfill", bind(TracePayloadBackfillJob.class),
                Duration.ofMinutes(TracePayloadBackfillJob.TICK_MINUTES));
        }
        bind(StaleRunCleanupService.class);
        schedule().fixedRate("stale-run-cleanup", bind(StaleRunCleanupJob.class), Duration.ofMinutes(10));
        registerTrace();
    }

    private void registerTrace() {
        bind(TraceService.class);
        bind(TraceVisibilityResolver.class);
        bind(TraceAccessControl.class);
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(PromptTemplateStore.class, bind(PgPromptTemplateStore.class));
        } else {
            bind(PromptTemplateStore.class, bind(MongoPromptTemplateStore.class));
        }
        bind(PromptService.class);
        bind(ModelPricingService.class);
        var otlpIngestService = bind(OTLPIngestService.class);
        var ingestService = bind(IngestService.class);
        var traceBuffer = new MemoryTraceSpanBuffer(otlpIngestService, ingestService,
                intProperty("sys.trace.ingest.queue.capacity", 10000),
                longProperty("sys.trace.ingest.handoff.bytes", 64L * 1024 * 1024),
                longProperty("sys.trace.ingest.max.record.bytes", 9L * 1024 * 1024));
        var shutdownTimeout = Duration.ofSeconds(longProperty("sys.trace.ingest.shutdown.timeout.seconds", 10L));
        bind(traceBuffer);
        onStartup(traceBuffer::start);
        onShutdown(() -> traceBuffer.shutdown(shutdownTimeout));

        // Register the shared hand-off buffer and the trace-created hook for local span ingestion (SPI bridge).
        onStartup(() -> {
            LocalSpanProcessorRegistry.register(otlpIngestService);
            LocalSpanProcessorRegistry.registerBuffer(traceBuffer);
        });

        var otlpController = bind(OTLPController.class);
        var ingestController = bind(IngestController.class);

        api().service(TraceWebService.class, bind(TraceWebServiceImpl.class));
        api().service(PromptWebService.class, bind(PromptWebServiceImpl.class));

        http().route(HTTPMethod.POST, "/v1/traces", otlpController::receive);
        http().route(HTTPMethod.POST, "/api/public/otel/v1/traces", otlpController::receive);
        http().route(HTTPMethod.POST, "/api/ingest/spans", ingestController::ingestSpans);
        // Authenticated ingest for CLI/SDK: AuthInterceptor resolves userId from Bearer (not whitelisted),
        // server overrides user attribution and stamps source from the clientType the client declared
        // (cli by default, desktop when the desktop app drives the engine). Distinct HTTP method from
        // GET /api/traces/:traceId.
        http().route(HTTPMethod.POST, "/api/traces/ingest", ingestController::ingestAuthed);
        http().route(HTTPMethod.POST, "/api/traces/ingest/start", ingestController::ingestStartAuthed);
        // On-demand image fetch for detail views whose payload exceeded the hydration budget.
        // core-ng path variables must be letters only, hence ":hash" rather than ":sha256".
        http().route(HTTPMethod.GET, "/api/traces/:traceId/payloads/:hash", bind(TracePayloadController.class));
        // Full span body for detail views whose payload exceeded the hydration budget.
        http().route(HTTPMethod.GET, "/api/traces/:traceId/spans/:spanId/body", bind(TraceBodyController.class));
    }

    /**
     * Resolve archive blob prefix for multi-environment isolation.
     * Priority: {@code trace.archive.blob.prefix} config property, then
     * K8s namespace file (for pod deployments), then null (no prefix).
     */
    private String resolveArchivePrefix() {
        String prefix = property("trace.archive.blob.prefix").orElse(null);
        if (prefix != null) {
            LOGGER.info("trace archive prefix from config: {}", prefix);
            return prefix;
        }
        try {
            Path namespaceFile = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
            if (Files.exists(namespaceFile)) {
                prefix = Files.readString(namespaceFile).strip();
                LOGGER.info("trace archive prefix from k8s namespace: {}", prefix);
                return prefix;
            }
        } catch (IOException e) {
            LOGGER.warn("failed to read k8s namespace file", e);
        }
        LOGGER.info("no trace archive prefix configured, blobs stored at root");
        return null;
    }

    private int intProperty(String key, int defaultValue) {
        var value = property(key).orElse(null);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.warn("invalid int property {}, using {}", key, defaultValue);
            return defaultValue;
        }
    }

    private long longProperty(String key, long defaultValue) {
        var value = property(key).orElse(null);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            LOGGER.warn("invalid long property {}, using {}", key, defaultValue);
            return defaultValue;
        }
    }
}
