package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.api.server.auth.AuthWebService;
import ai.core.api.server.FileWebService;
import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.SkillWebService;
import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.UserWebService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.auth.AuthService;
import ai.core.server.llmcall.LLMCallBuilderTools;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileUploadController;
import ai.core.server.domain.migration.SchemaMigrationManager;
import ai.core.server.run.AgentRunService;
import ai.core.server.run.LLMCallExecutor;
import ai.core.server.run.AgentRunner;
import ai.core.server.schedule.AgentScheduleService;
import ai.core.server.schedule.AgentScheduler;
import ai.core.server.schedule.AgentSchedulerJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillUploadController;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.user.UserService;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
import ai.core.server.web.SkillWebServiceImpl;
import ai.core.server.web.webhook.WebhookController;
import ai.core.server.web.AgentRunWebServiceImpl;
import ai.core.server.web.AgentScheduleWebServiceImpl;
import ai.core.server.web.sse.AgentSessionChannelListener;
import ai.core.server.web.sse.ChannelService;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.server.web.AgentSessionWebServiceImpl;
import ai.core.server.web.FileWebServiceImpl;
import ai.core.server.web.ToolRegistryWebServiceImpl;
import ai.core.server.web.AuthWebServiceImpl;
import ai.core.server.web.UserWebServiceImpl;
import ai.core.server.systemprompt.SystemPromptController;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.web.StaticFileController;
import ai.core.server.trace.service.IngestService;
import ai.core.server.trace.service.OTLPIngestService;
import ai.core.server.trace.service.PromptService;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.web.ingest.IngestController;
import ai.core.server.trace.web.otlp.OTLPController;
import ai.core.server.trace.web.prompt.PromptController;
import ai.core.server.trace.web.trace.TraceController;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.sse.PatchedServerSentEventConfig;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @author stephen
 */
public class ServerModule extends Module {
    @Override
    protected void initialize() {
        var migrationManager = bind(SchemaMigrationManager.class);
        onStartup(migrationManager::migrate);

        http().intercept(bind(AuthInterceptor.class));

        var toolRegistry = bind(ToolRegistryService.class);

        registerFile();
        registerSkill();

        bind(SystemPromptService.class);
        bind(LLMCallExecutor.class);
        bind(AgentRunner.class);
        bind(AgentScheduler.class);
        bind(AgentSessionManager.class);
        bind(AgentDefinitionService.class);
        bind(AgentDraftGenerator.class);
        bind(AgentRunService.class);
        bind(AgentScheduleService.class);
        bind(UserService.class);
        var authService = bind(AuthService.class);
        authService.adminEmail = property("sys.admin.email").orElse("admin@example.com");
        authService.adminPassword = property("sys.admin.password").orElse("admin");
        authService.adminName = property("sys.admin.name").orElse("Admin");
        bind(ChannelService.class);
        bind(SessionChannelService.class);

        var builderTools = bind(LLMCallBuilderTools.class);

        onStartup(toolRegistry::initialize);
        onStartup(builderTools::initialize);
        onStartup(authService::initialize);

        api().service(AuthWebService.class, bind(AuthWebServiceImpl.class));
        api().service(UserWebService.class, bind(UserWebServiceImpl.class));
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        api().service(ToolRegistryWebService.class, bind(ToolRegistryWebServiceImpl.class));
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
        api().service(AgentRunWebService.class, bind(AgentRunWebServiceImpl.class));
        api().service(AgentScheduleWebService.class, bind(AgentScheduleWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/webhooks/:agentId", bind(WebhookController.class));

        schedule().fixedRate("agent-scheduler", bind(AgentSchedulerJob.class), Duration.ofMinutes(1));

        registerTrace();
        registerSystemPrompt();

        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.listen(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class));

        registerStaticFiles();
    }

    private void registerStaticFiles() {
        var webPath = System.getProperty("core.webPath");
        if (webPath == null) return;
        var webDir = Path.of(webPath);
        if (!webDir.toFile().exists()) return;
        var controller = new StaticFileController(webDir);
        // static assets
        http().route(HTTPMethod.GET, "/favicon.svg", controller::serve);
        http().route(HTTPMethod.GET, "/icons.svg", controller::serve);
        http().route(HTTPMethod.GET, "/assets/:file", controller::serve);
        // SPA routes (return index.html for all frontend paths)
        var spaRoutes = new String[]{
            "/", "/login", "/chat", "/agents", "/sessions",
            "/system-prompts", "/dashboard", "/traces"
        };
        for (var path : spaRoutes) {
            http().route(HTTPMethod.GET, path, controller::serve);
        }
        // SPA dynamic routes
        http().route(HTTPMethod.GET, "/agents/:id", controller::serve);
        http().route(HTTPMethod.GET, "/runs/:id", controller::serve);
        http().route(HTTPMethod.GET, "/system-prompts/:id", controller::serve);
        http().route(HTTPMethod.GET, "/traces/:id", controller::serve);
    }

    private void registerFile() {
        var storagePath = property("sys.file.storagePath").orElse("./data/files");
        bind(new FileService(Path.of(storagePath)));
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));
    }

    private void registerSkill() {
        bind(MongoSkillProvider.class);
        bind(SkillService.class);
        api().service(SkillWebService.class, bind(SkillWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/skills/upload", bind(SkillUploadController.class));
//        schedule().fixedRate("skill-repo-sync", bind(SkillRepoSyncJob.class), Duration.ofHours(1));
    }

    private void registerSystemPrompt() {
        var controller = bind(SystemPromptController.class);

        http().route(HTTPMethod.GET, "/api/system-prompts", controller::list);
        http().route(HTTPMethod.POST, "/api/system-prompts", controller::create);
        http().route(HTTPMethod.GET, "/api/system-prompts/:promptId", controller::get);
        http().route(HTTPMethod.PUT, "/api/system-prompts/:promptId", controller::update);
        http().route(HTTPMethod.DELETE, "/api/system-prompts/:promptId", controller::delete);
        http().route(HTTPMethod.GET, "/api/system-prompts/:promptId/versions", controller::versions);
        http().route(HTTPMethod.GET, "/api/system-prompts/:promptId/versions/:version", controller::getVersion);
        http().route(HTTPMethod.POST, "/api/system-prompts/:promptId/test", controller::test);
    }

    private void registerTrace() {
        bind(TraceService.class);
        bind(PromptService.class);
        bind(OTLPIngestService.class);
        bind(IngestService.class);

        var traceController = bind(TraceController.class);
        var promptController = bind(PromptController.class);
        var otlpController = bind(OTLPController.class);
        var ingestController = bind(IngestController.class);

        http().route(HTTPMethod.GET, "/api/traces", traceController::list);
        http().route(HTTPMethod.GET, "/api/traces/generations", traceController::generations);
        http().route(HTTPMethod.GET, "/api/traces/sessions", traceController::sessions);
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
    }
}