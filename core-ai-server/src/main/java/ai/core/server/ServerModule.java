package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.api.server.auth.AuthWebService;
import ai.core.api.server.DatasetWebService;
import ai.core.api.server.FileWebService;
import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.SkillWebService;
import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.UserWebService;
import ai.core.api.server.trigger.TriggerWebService;
import ai.core.api.a2a.StreamResponse;
import ai.core.a2a.A2AHttpPaths;
import ai.core.server.a2a.A2AAgentCardController;
import ai.core.server.a2a.A2AMessageController;
import ai.core.server.a2a.A2AStreamChannelListener;
import ai.core.server.a2a.A2ATaskController;
import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.agent.GenerateService;
import ai.core.server.agent.JavaToSchemaService;
import ai.core.server.auth.AuthService;
import ai.core.server.blob.AzureBlobSasService;
import ai.core.server.blob.BlobUploadCredentialController;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.server.agentbuilder.AgentBuilderTools;
import ai.core.server.llmcall.LLMCallBuilderTools;
import ai.core.server.web.CorsInterceptor;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileUploadController;
import ai.core.server.file.SharedFileDownloadController;
import ai.core.server.github.GitHubInstallationTokenService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.migration.SchemaMigrationManager;
import ai.core.server.run.AgentRunService;
import ai.core.server.run.LLMCallExecutor;
import ai.core.server.run.AgentRunner;
import ai.core.server.schedule.AgentScheduleService;
import ai.core.server.schedule.AgentScheduler;
import ai.core.server.schedule.AgentSchedulerJob;
import ai.core.server.schedule.IdleSessionCleanupJob;
import ai.core.server.schedule.ToolRegistrySyncJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillUploadController;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.user.UserService;
import ai.core.server.trigger.TriggerController;
import ai.core.server.trigger.TriggerService;
import ai.core.server.trigger.action.RunAgentAction;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
import ai.core.server.web.ChatSessionController;
import ai.core.server.web.SessionCreateHelper;
import ai.core.server.web.DatasetWebServiceImpl;
import ai.core.server.web.SkillWebServiceImpl;
import ai.core.server.web.AgentRunWebServiceImpl;
import ai.core.server.web.AgentScheduleWebServiceImpl;
import ai.core.server.web.sse.AgentSessionChannelListener;
import ai.core.server.web.sse.ChannelService;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.server.web.AgentSessionWebServiceImpl;
import ai.core.server.web.FileWebServiceImpl;
import ai.core.server.web.PodLocalExecutor;
import ai.core.server.web.ToolRegistryWebServiceImpl;
import ai.core.server.web.AuthWebServiceImpl;
import ai.core.server.web.UserWebServiceImpl;
import ai.core.server.web.TriggerWebServiceImpl;
import ai.core.server.systemprompt.SystemPromptController;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.web.CapabilitiesController;
import ai.core.server.web.SpeechController;
import ai.core.server.web.StaticFileController;
import ai.core.server.web.foryou.ForYouController;
import ai.core.server.web.foryou.ForYouService;
import ai.core.server.trace.service.IngestService;
import ai.core.server.trace.service.OTLPIngestService;
import ai.core.server.trace.service.PromptService;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.spi.LocalSpanProcessorRegistry;
import ai.core.server.trace.web.ingest.IngestController;
import ai.core.server.trace.web.otlp.OTLPController;
import ai.core.server.trace.web.prompt.PromptController;
import ai.core.server.trace.web.trace.TraceController;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.sse.PatchedServerSentEventConfig;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @author stephen
 */
public class ServerModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerModule.class);

    @Override
    protected void initialize() {
        var migrationManager = bind(SchemaMigrationManager.class);
        onStartup(migrationManager::migrate);
        bind(RequestAuthenticator.class);
        http().intercept(bind(AuthInterceptor.class));
        var corsInterceptor = bind(CorsInterceptor.class);
        http().intercept(corsInterceptor);
        http().errorHandler(corsInterceptor);
        var toolRegistry = bind(ToolRegistryService.class);
        registerFile();
        registerSkill();
        site().session().timeout(Duration.ofHours(24));
        site().session().cookie("CoreAIServerSessionId", null);
        load(new SandboxModule());
        bindService();
        bindAuthService();
        bindGitHubService();
        registerWebhookTrigger();
        var builderTools = bind(LLMCallBuilderTools.class);
        var agentBuilderTools = bind(AgentBuilderTools.class);
        var mcpConfig = property("mcp.servers.json").orElse(null);
        onStartup(() -> toolRegistry.initialize(mcpConfig));
        onStartup(builderTools::initialize);
        onStartup(agentBuilderTools::initialize);
        load(new MessagingModule());
        bind(PodLocalExecutor.class);
        bindWebService();
        schedule().fixedRate("agent-scheduler", bind(AgentSchedulerJob.class), Duration.ofMinutes(1));
        schedule().fixedRate("tool-registry-sync", bind(ToolRegistrySyncJob.class), Duration.ofSeconds(30));
        schedule().fixedRate("idle-session-cleanup", bind(IdleSessionCleanupJob.class), Duration.ofMinutes(5));
        registerTrace();
        registerSystemPrompt();
        registerCapabilities();
        registerForYou();
        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.listen(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class));
        sseConfig.listen(HTTPMethod.POST, "/api/a2a" + A2AHttpPaths.MESSAGE_STREAM, StreamResponse.class, bind(A2AStreamChannelListener.class));
        registerStaticFiles();
    }

    private void bindAuthService() {
        var authService = bind(AuthService.class);
        authService.adminEmail = property("sys.admin.email").orElse("admin@example.com");
        authService.adminPassword = property("sys.admin.password").orElse("admin");
        authService.adminName = property("sys.admin.name").orElse("Admin");

        onStartup(authService::initialize);
    }

    private void bindGitHubService() {
        var appId = property("github.app.id").orElse(null);
        var privateKey = property("github.app.private_key").orElse(null);
        var installationId = property("github.app.installation_id")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .orElse(null);
        if (appId != null && !appId.isBlank() && installationId != null && privateKey != null && !privateKey.isBlank() && privateKey.contains("BEGIN")) {
            var githubService = new GitHubInstallationTokenService(appId, privateKey, installationId);
            githubService.register();
            LOGGER.info("GitHub installation token service configured");
        } else {
            LOGGER.info("GitHub App not configured (github.app.id or github.app.private_key missing), GitHub token tool will be unavailable");
        }
    }

    private void bindService() {
        var publicUrl = property("sys.public.url").orElse("http://localhost:8080");
        ai.core.server.run.SubmitArtifactsTool.publicUrl = publicUrl;
        ai.core.server.agentbuilder.CreateAgentDraftTool.publicUrl = publicUrl;
        ai.core.server.agentbuilder.PublishAgentDraftTool.publicUrl = publicUrl;
        bind(SystemPromptService.class);
        bind(LLMCallExecutor.class);
        bind(DatasetService.class);
        bind(DatasetRecordService.class);
        bind(AgentRunner.class);
        bind(AgentScheduler.class);
        bind(ChannelService.class);
        bind(SessionChannelService.class);
        bind(ChatMessageService.class);
        bind(ai.core.server.artifact.ChatArtifactSetup.class);
        bind(AgentSessionManager.class);
        bind(AgentDefinitionService.class);
        bind(ServerA2AService.class);
        bind(JavaToSchemaService.class);
        bind(AgentDraftGenerator.class);
        bind(GenerateService.class);
        bind(AgentRunService.class);
        bind(AgentScheduleService.class);
        bind(UserService.class);
        var triggerService = bind(TriggerService.class);
        triggerService.publicUrl = publicUrl;
        bind(RunAgentAction.class);
        bind(ForYouService.class);
        bind(SessionCreateHelper.class);
    }

    private void bindWebService() {
        var chatSessionController = bind(ChatSessionController.class);
        http().route(HTTPMethod.GET, "/api/chat/sessions", chatSessionController::list);
        http().route(HTTPMethod.GET, "/api/chat/sessions/:sessionId", chatSessionController::get);
        http().route(HTTPMethod.DELETE, "/api/chat/sessions/:sessionId", chatSessionController::delete);

        // Speech token exchange for browser-side Azure Speech SDK
        var speechController = bind(SpeechController.class);
        speechController.speechKey = property("azure.speech.key").orElse(null);
        speechController.speechRegion = property("azure.speech.region").orElse("eastus");
        speechController.speechEndpoint = property("azure.speech.endpoint").orElse(null);
        http().route(HTTPMethod.GET, "/api/speech/token", speechController::getToken);

        // Blob upload credential for direct browser-to-Azure uploads
        var blobController = bind(BlobUploadCredentialController.class);
        var accountName = property("azure.blob.account.name").orElse(null);
        var accountKey = property("azure.blob.account.key").orElse(null);
        blobController.container = property("azure.blob.container").orElse("uploads");
        blobController.prefix = property("azure.blob.prefix").orElse(null);
        blobController.publicBaseUrl = property("azure.blob.public.base.url").orElse(null);
        blobController.sasService = AzureBlobSasService.tryCreate(accountName, accountKey);
        if (blobController.sasService != null) {
            LOGGER.info("Azure Blob upload credential endpoint configured (container={})", blobController.container);
        } else {
            LOGGER.info("Azure Blob Storage not configured (azure.blob.account.name/key missing or invalid), upload endpoint will return 500");
        }
        http().bean(BlobUploadCredentialView.class);
        http().route(HTTPMethod.GET, "/api/blob/upload-credential", blobController::getCredential);

        api().service(AuthWebService.class, bind(AuthWebServiceImpl.class));
        api().service(UserWebService.class, bind(UserWebServiceImpl.class));
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        api().service(ToolRegistryWebService.class, bind(ToolRegistryWebServiceImpl.class));
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
        api().service(AgentRunWebService.class, bind(AgentRunWebServiceImpl.class));
        api().service(AgentScheduleWebService.class, bind(AgentScheduleWebServiceImpl.class));
        api().service(TriggerWebService.class, bind(TriggerWebServiceImpl.class));
        api().service(DatasetWebService.class, bind(DatasetWebServiceImpl.class));
        registerA2A();
    }

    private void registerA2A() {
        var agentCardController = bind(A2AAgentCardController.class);
        var messageController = bind(A2AMessageController.class);
        var taskController = bind(A2ATaskController.class);

        http().route(HTTPMethod.GET, "/api/a2a/agents/:agentId/.well-known/agent-card.json", agentCardController::get);
        http().route(HTTPMethod.POST, "/api/a2a" + A2AHttpPaths.MESSAGE_SEND, messageController::send);
        http().route(HTTPMethod.POST, "/api/a2a/agents/:agentId/message/send", messageController::send);
        http().route(HTTPMethod.GET, "/api/a2a/tasks/:taskId", taskController::get);
        http().route(HTTPMethod.POST, "/api/a2a/tasks/:taskId" + A2AHttpPaths.TASK_CANCEL, taskController::cancel);
    }

    private void registerStaticFiles() {
        var webPath = System.getProperty("core.webPath");
        if (webPath == null) return;
        var webDir = Path.of(webPath);
        if (!webDir.toFile().exists()) return;
        var controller = new StaticFileController(webDir);
        http().route(HTTPMethod.GET, "/favicon.svg", controller::serve);
        http().route(HTTPMethod.GET, "/favicon.ico", controller::serve);
        http().route(HTTPMethod.GET, "/icons.svg", controller::serve);
        http().route(HTTPMethod.GET, "/logo-lockup.svg", controller::serve);
        http().route(HTTPMethod.GET, "/logo-lockup-dark.svg", controller::serve);
        http().route(HTTPMethod.GET, "/apple-touch-icon.png", controller::serveAppleTouchIcon);
        http().route(HTTPMethod.GET, "/assets/:file", controller::serve);
        // iOS Safari legacy probe; reuse favicon.svg to silence 404 noise.
        http().route(HTTPMethod.GET, "/apple-touch-icon-precomposed.png", controller::serveAppleTouchIcon);
        var spaRoutes = new String[]{
            "/", "/login", "/chat", "/agents", "/sessions",
            "/system-prompts", "/dashboard", "/traces", "/skills",
            "/prompts", "/scheduler", "/tasks", "/tools", "/api-tools",
            "/triggers", "/datasets", "/for-you"
        };
        for (var path : spaRoutes) {
            http().route(HTTPMethod.GET, path, controller::serve);
        }
        http().route(HTTPMethod.GET, "/agents/:id", controller::serve);
        http().route(HTTPMethod.GET, "/runs/:id", controller::serve);
        http().route(HTTPMethod.GET, "/system-prompts/:id", controller::serve);
        http().route(HTTPMethod.GET, "/traces/:id", controller::serve);
        http().route(HTTPMethod.GET, "/skills/:id", controller::serve);
        http().route(HTTPMethod.GET, "/skills/:id/edit", controller::serve);
        http().route(HTTPMethod.GET, "/prompts/:id", controller::serve);
        http().route(HTTPMethod.GET, "/api-tools/:id", controller::serve);
        http().route(HTTPMethod.GET, "/datasets/:id", controller::serve);
        http().route(HTTPMethod.GET, "/datasets/:id/records", controller::serve);
        http().route(HTTPMethod.GET, "/shared/artifacts/:token", controller::serve);
        // nested SPA routes (multi-segment paths that need direct URL access / refresh support)
        http().route(HTTPMethod.GET, "/triggers/webhook", controller::serve);
        http().route(HTTPMethod.GET, "/triggers/schedule", controller::serve);
        http().route(HTTPMethod.GET, "/tools/builtin", controller::serve);
        http().route(HTTPMethod.GET, "/settings/users", controller::serve);
    }
    private void registerFile() {
        bind(FileService.class);
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));
        http().route(HTTPMethod.GET, "/api/public/artifacts/:token/content", bind(SharedFileDownloadController.class));
    }
    private void registerSkill() {
        bind(SkillService.class);
        bind(MongoSkillProvider.class);
        bind(new SkillArchiveBuilder());
        api().service(SkillWebService.class, bind(SkillWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/skills/upload", bind(SkillUploadController.class));
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
    private void registerWebhookTrigger() {
        var controller = bind(TriggerController.class);
        http().route(HTTPMethod.POST, "/api/webhook-triggers/:id", controller);
        // GET for Slack URL verification challenge
        http().route(HTTPMethod.GET, "/api/webhook-triggers/:id", controller);
    }
    private void registerCapabilities() {
        var controller = bind(CapabilitiesController.class);
        controller.authDisabled = property("sys.auth.disabled").orElse("false").equals("true");
        http().route(HTTPMethod.GET, "/api/capabilities", controller::get);
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

    private void registerForYou() {
        var controller = bind(ForYouController.class);

        http().route(HTTPMethod.GET, "/api/for-you", controller::dashboard);
        http().route(HTTPMethod.GET, "/api/for-you/reports", controller::listReports);
        http().route(HTTPMethod.POST, "/api/for-you/reports", controller::createReport);
        http().route(HTTPMethod.PUT, "/api/for-you/reports/:id", controller::updateReport);
        http().route(HTTPMethod.DELETE, "/api/for-you/reports/:id", controller::deleteReport);
        http().route(HTTPMethod.GET, "/api/for-you/todos", controller::listTodos);
        http().route(HTTPMethod.POST, "/api/for-you/todos", controller::createTodo);
        http().route(HTTPMethod.PUT, "/api/for-you/todos/:id", controller::updateTodo);
        http().route(HTTPMethod.DELETE, "/api/for-you/todos/:id", controller::deleteTodo);
        http().route(HTTPMethod.GET, "/api/for-you/files", controller::listFiles);
        http().route(HTTPMethod.GET, "/api/for-you/token-usage", controller::tokenUsage);
    }
}
