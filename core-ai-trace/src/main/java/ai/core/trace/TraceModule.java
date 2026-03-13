package ai.core.trace;

import core.framework.module.Module;

import ai.core.trace.domain.migration.SchemaMigrationManager;
import ai.core.trace.service.PromptService;
import ai.core.trace.service.TraceService;
import ai.core.trace.web.otlp.OTLPController;
import ai.core.trace.web.prompt.PromptController;
import ai.core.trace.web.trace.TraceController;

/**
 * @author Xander
 */
public class TraceModule extends Module {
    @Override
    protected void initialize() {
        var migrationManager = bind(SchemaMigrationManager.class);
        onStartup(migrationManager::migrate);

        bind(TraceService.class);
        bind(PromptService.class);

        var traceController = bind(TraceController.class);
        var promptController = bind(PromptController.class);
        var otlpController = bind(OTLPController.class);

        // trace APIs
        http().route(core.framework.http.HTTPMethod.GET, "/api/traces", traceController::list);
        http().route(core.framework.http.HTTPMethod.GET, "/api/traces/:traceId", traceController::get);
        http().route(core.framework.http.HTTPMethod.GET, "/api/traces/:traceId/spans", traceController::spans);

        // prompt management APIs
        http().route(core.framework.http.HTTPMethod.GET, "/api/prompts", promptController::list);
        http().route(core.framework.http.HTTPMethod.POST, "/api/prompts", promptController::create);
        http().route(core.framework.http.HTTPMethod.GET, "/api/prompts/:promptId", promptController::get);
        http().route(core.framework.http.HTTPMethod.PUT, "/api/prompts/:promptId", promptController::update);
        http().route(core.framework.http.HTTPMethod.DELETE, "/api/prompts/:promptId", promptController::delete);
        http().route(core.framework.http.HTTPMethod.POST, "/api/prompts/:promptId/publish", promptController::publish);

        // OTLP receiver
        http().route(core.framework.http.HTTPMethod.POST, "/v1/traces", otlpController::receive);
    }
}
