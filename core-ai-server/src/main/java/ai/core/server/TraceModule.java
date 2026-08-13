package ai.core.server;

import ai.core.api.server.prompt.PromptWebService;
import ai.core.api.server.trace.TraceWebService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.task.TaskRunner;
import ai.core.server.trace.maintenance.TraceArchiveService;
import ai.core.server.trace.maintenance.TraceArchivingJob;
import ai.core.server.trace.maintenance.TraceArchivingTask;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceJob;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceService;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceTask;
import ai.core.server.trace.service.IngestService;
import ai.core.server.trace.service.ModelPricingService;
import ai.core.server.trace.service.OTLPIngestService;
import ai.core.server.trace.service.PromptService;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.spi.LocalSpanProcessorRegistry;
import ai.core.server.trace.web.ingest.IngestController;
import ai.core.server.trace.web.otlp.OTLPController;
import ai.core.server.trace.web.prompt.PromptWebServiceImpl;
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
        bind(TraceDailyMaintenanceService.class);
        var configuredContainer = property("trace.archive.container").orElse(null);
        var archiveService = new TraceArchiveService(bean(ObjectStorageServiceResolver.class), configuredContainer, resolveArchivePrefix());
        bind(archiveService);
        var traceDailyMaintenanceTask = bind(TraceDailyMaintenanceTask.class);
        var taskRunner = bean(TaskRunner.class);
        onStartup(() -> taskRunner.register(traceDailyMaintenanceTask));
        schedule().fixedRate("trace-daily-maintenance", bind(TraceDailyMaintenanceJob.class), Duration.ofHours(1));
        var traceArchivingTask = bind(TraceArchivingTask.class);
        onStartup(() -> taskRunner.register(traceArchivingTask));
        schedule().fixedRate("trace-archive", bind(TraceArchivingJob.class), Duration.ofHours(1));
        registerTrace();
    }

    private void registerTrace() {
        bind(TraceService.class);
        bind(PromptService.class);
        bind(ModelPricingService.class);
        var otlpIngestService = bind(OTLPIngestService.class);
        bind(IngestService.class);

        // Register OTLPIngestService for LocalSpanProcessor (SPI bridge)
        onStartup(() -> LocalSpanProcessorRegistry.register(otlpIngestService));

        var otlpController = bind(OTLPController.class);
        var ingestController = bind(IngestController.class);

        api().service(TraceWebService.class, bind(TraceWebServiceImpl.class));
        api().service(PromptWebService.class, bind(PromptWebServiceImpl.class));

        http().route(HTTPMethod.POST, "/v1/traces", otlpController::receive);
        http().route(HTTPMethod.POST, "/api/public/otel/v1/traces", otlpController::receive);
        http().route(HTTPMethod.POST, "/api/ingest/spans", ingestController::ingestSpans);
        // Authenticated ingest for CLI/SDK: AuthInterceptor resolves userId from Bearer (not whitelisted),
        // server overrides user attribution and stamps source=cli. Distinct HTTP method from GET /api/traces/:traceId.
        http().route(HTTPMethod.POST, "/api/traces/ingest", ingestController::ingestAuthed);
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
}
