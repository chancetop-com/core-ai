package ai.core.server;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.task.TaskRunner;
import ai.core.server.trace.maintenance.TraceArchiveService;
import ai.core.server.trace.maintenance.TraceArchivingJob;
import ai.core.server.trace.maintenance.TraceArchivingTask;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceJob;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceService;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceTask;
import ai.core.server.trace.service.IngestService;
import ai.core.server.trace.service.OTLPIngestService;
import ai.core.server.trace.service.PromptService;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.spi.LocalSpanProcessorRegistry;
import ai.core.server.trace.web.ingest.IngestController;
import ai.core.server.trace.web.otlp.OTLPController;
import ai.core.server.trace.web.prompt.PromptController;
import ai.core.server.trace.web.trace.TraceController;
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
        var archiveService = bind(TraceArchiveService.class);
        try {
            var objectStorage = (ObjectStorageService) context.beanFactory.bean(ObjectStorageService.class, null);
            archiveService.setStorageService(objectStorage);
        } catch (Error e) {
            archiveService.setStorageService(null);
        }
        var container = property("trace.archive.container").orElse(property("azure.blob.multimodal.container").orElse("traces-archive"));
        archiveService.setArchiveContainer(container);
        archiveService.setArchivePrefix(resolveArchivePrefix());
        var traceDailyMaintenanceTask = bind(TraceDailyMaintenanceTask.class);
        var taskRunner = bind(TaskRunner.class);   // must be before bindService() — TaskController injects it
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
        var otlpIngestService = bind(OTLPIngestService.class);
        bind(IngestService.class);

        // Register OTLPIngestService for LocalSpanProcessor (SPI bridge)
        onStartup(() -> LocalSpanProcessorRegistry.register(otlpIngestService));

        var traceController = bind(TraceController.class);
        var promptController = bind(PromptController.class);
        var otlpController = bind(OTLPController.class);
        var ingestController = bind(IngestController.class);

        http().route(HTTPMethod.GET, "/api/traces", traceController::list);
        http().route(HTTPMethod.GET, "/api/traces/facets", traceController::facets);
        http().route(HTTPMethod.GET, "/api/traces/generations", traceController::generations);
        http().route(HTTPMethod.GET, "/api/traces/sessions/:sessionId/summary", traceController::sessionSummary);
        http().route(HTTPMethod.GET, "/api/traces/:traceId", traceController::get);
        http().route(HTTPMethod.GET, "/api/traces/:traceId/spans", traceController::spans);

        http().route(HTTPMethod.GET, "/api/prompts", promptController::list);
        http().route(HTTPMethod.POST, "/api/prompts", promptController::create);
        http().route(HTTPMethod.GET, "/api/prompts/:promptId", promptController::get);
        http().route(HTTPMethod.PUT, "/api/prompts/:promptId", promptController::update);
        http().route(HTTPMethod.DELETE, "/api/prompts/:promptId", promptController::delete);
        http().route(HTTPMethod.POST, "/api/prompts/:promptId/publish", promptController::publish);

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
