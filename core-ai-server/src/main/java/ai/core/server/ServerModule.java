package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.api.server.ArtifactWebService;
import ai.core.api.server.auth.AuthWebService;
import ai.core.api.server.DatasetWebService;
import ai.core.api.server.SkillWebService;
import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.NotificationWebService;
import ai.core.api.server.notification.NotificationSseEvent;
import ai.core.api.server.UserWebService;
import ai.core.api.server.settings.SystemSettingsWebService;
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
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.artifact.ArtifactService;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.auth.AuthService;
import ai.core.server.agentbuilder.AgentBuilderTools;
import ai.core.server.llmcall.LLMCallBuilderTools;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.selfharness.SelfHarnessApiCaller;
import ai.core.server.selfharness.SelfHarnessTools;
import ai.core.server.web.sse.LiteLLMProxyChannelListener;
import ai.core.server.web.sse.SseAuthInterceptor;
import ai.core.server.web.CorsInterceptor;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.github.GitHubInstallationTokenService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.migration.SchemaMigrationManager;
import ai.core.server.run.LLMCallExecutor;
import ai.core.server.schedule.IdleSessionCleanupJob;
import ai.core.server.schedule.ToolRegistrySyncJob;
import ai.core.server.sandbox.snapshot.SandboxSnapshotCleanupJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.skill.MarketplaceService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillUploadController;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.notification.NotificationEventPublisher;
import ai.core.server.notification.NotificationService;
import ai.core.server.notification.NotificationTools;
import ai.core.server.notification.SendNotificationHandler;
import ai.core.server.user.UserService;
import ai.core.server.trigger.TriggerService;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.channel.openclaw.OcgCallbackPool;
import ai.core.server.channel.openclaw.OcgConfigStore;
import ai.core.server.channel.openclaw.OcgHealthCheckJob;
import ai.core.server.channel.openclaw.OcgSandboxService;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
import ai.core.server.web.ArtifactWebServiceImpl;
import ai.core.server.web.ChatSessionController;
import ai.core.server.web.SessionCreateHelper;
import ai.core.server.web.DatasetWebServiceImpl;
import ai.core.server.web.SkillWebServiceImpl;
import ai.core.server.web.sse.AgentSessionChannelListener;
import ai.core.server.web.sse.NotificationChannelListener;
import ai.core.server.web.ToolRegistryWebServiceImpl;
import ai.core.server.web.NotificationWebServiceImpl;
import ai.core.server.web.AuthWebServiceImpl;
import ai.core.server.web.UserWebServiceImpl;
import ai.core.server.web.SystemSettingsWebServiceImpl;
import ai.core.server.web.TriggerWebServiceImpl;
import ai.core.server.systemprompt.SystemPromptController;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.web.CapabilitiesController;
import ai.core.server.web.SpeechController;
import ai.core.server.web.foryou.ForYouController;
import ai.core.server.web.foryou.ForYouService;
import ai.core.server.analytics.AdminAnalyticsController;
import ai.core.server.analytics.AdminAnalyticsService;
import ai.core.server.analytics.AnalyticsMappingService;
import ai.core.server.task.TaskController;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.sse.PatchedServerSentEventConfig;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * @author stephen
 */
public class ServerModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerModule.class);

    @Override
    protected void initialize() {
        setupPreBindServices();
        bindService();
        bindAuthService();
        bindGitHubService();
        var builderTools = bind(LLMCallBuilderTools.class);
        var agentBuilderTools = bind(AgentBuilderTools.class);
        onStartup(builderTools::initialize);
        onStartup(agentBuilderTools::initialize);

        bindWebService();
        bindScheduledJobs();
        bindDevTools();
        bindSseAndRegister();
    }

    private void setupPreBindServices() {
        var migrationManager = bind(SchemaMigrationManager.class);
        onStartup(migrationManager::migrate);
        bind(RequestAuthenticator.class);
        bind(ChannelConfigStore.class);         // must be before AuthInterceptor — AuthInterceptor checks per-channel auth
        bind(OcgConfigStore.class);
        bind(ChannelRegistry.class);
        http().intercept(bind(AuthInterceptor.class));
        var corsInterceptor = bind(CorsInterceptor.class);
        http().intercept(corsInterceptor);
        http().errorHandler(corsInterceptor);
        var toolRegistryService = bind(ToolRegistryService.class);
        var mcpConfig = property("mcp.servers.json").orElse(null);
        onStartup(() -> toolRegistryService.initialize(mcpConfig));
        registerSkill();
        site().session().timeout(Duration.ofHours(24));
        site().session().cookie("CoreAIServerSessionId", null);

        var sandboxService = (SandboxService) context.beanFactory.bean(SandboxService.class, null);
        toolRegistryService.setSandboxService(sandboxService);

        // NotificationService must be bound before bindService() — WorkflowRunner injects it.
        // NotificationEventPublisher must be bound first — NotificationService injects it.
        bind(NotificationEventPublisher.class);
        bind(NotificationService.class);
    }

    private void bindScheduledJobs() {
        schedule().fixedRate("tool-registry-sync", bind(ToolRegistrySyncJob.class), Duration.ofSeconds(30));
        schedule().fixedRate("idle-session-cleanup", bind(IdleSessionCleanupJob.class), Duration.ofMinutes(5));
        schedule().fixedRate("sandbox-snapshot-cleanup", bind(SandboxSnapshotCleanupJob.class), Duration.ofHours(1));
        schedule().fixedRate("ocg-health-check", bind(OcgHealthCheckJob.class), Duration.ofMinutes(1));
    }

    private void bindDevTools() {
        bind(SelfHarnessApiCaller.class);
        var selfHarnessTools = bind(SelfHarnessTools.class);
        onStartup(selfHarnessTools::initialize);

        bind(SendNotificationHandler.class);
        var notificationTools = bind(NotificationTools.class);
        onStartup(notificationTools::initialize);
    }

    private void bindSseAndRegister() {
        registerSystemPrompt();
        registerCapabilities();
        registerForYou();
        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.intercept(new SseAuthInterceptor(bean(RequestAuthenticator.class)));
        sseConfig.listen(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class));
        sseConfig.listen(HTTPMethod.POST, "/api/a2a" + A2AHttpPaths.MESSAGE_STREAM, StreamResponse.class, bind(A2AStreamChannelListener.class));
        sseConfig.listen(HTTPMethod.POST, "/api/litellm/v1/chat/completions", Object.class, bind(LiteLLMProxyChannelListener.class));
        sseConfig.listen(HTTPMethod.PUT, "/api/notifications/events", NotificationSseEvent.class, bind(NotificationChannelListener.class));
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
        if (appId != null && installationId != null && privateKey != null && !appId.isBlank() && !privateKey.isBlank() && privateKey.contains("BEGIN")) {
            var githubService = new GitHubInstallationTokenService(appId, privateKey, installationId);
            githubService.register();
            LOGGER.info("GitHub installation token service configured");
        } else {
            LOGGER.info("GitHub App not configured (github.app.id or github.app.private_key missing), GitHub token tool will be unavailable");
        }
    }

    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    private void bindService() {
        var publicUrl = property("sys.public.url").orElse("http://localhost:8080");
        ai.core.server.run.SubmitArtifactsTool.publicUrl = publicUrl;
        bind(SystemPromptService.class);
        bind(LLMCallExecutor.class);
        bind(DatasetService.class);
        bind(DatasetRecordService.class);
        bind(SubAgentAssembler.class);
        bind(ChatArtifactSetup.class);
        bind(ChatMessageService.class);
        bind(AgentSessionManager.class);
        bind(AgentDefinitionService.class);
        bind(ServerA2AService.class);
        bind(JavaToSchemaService.class);
        bind(AgentDraftGenerator.class);
        bind(GenerateService.class);
        bind(UserService.class);
        var triggerService = bind(TriggerService.class);
        triggerService.publicUrl = publicUrl;
        var ocgSandboxService = bind(OcgSandboxService.class);
        ocgSandboxService.publicUrl = publicUrl;

        onStartup(ocgSandboxService::recoverOnStartup);
        var ocgCallbackPool = bind(OcgCallbackPool.class);
        onShutdown(ocgCallbackPool::shutdown);

        registerServiceRoutes();

        bind(ForYouService.class);
        bind(ArtifactService.class);
        bind(SessionCreateHelper.class);
    }

    private void registerServiceRoutes() {
        var taskController = bind(TaskController.class);
        http().route(HTTPMethod.GET, "/api/admin/tasks", taskController::list);
        http().route(HTTPMethod.PUT, "/api/admin/tasks/:taskId/retry", taskController::retry);
        http().route(HTTPMethod.POST, "/api/admin/tasks", taskController::run);

        registerAnalyticsRoutes();
    }

    private void registerAnalyticsRoutes() {
        bind(AnalyticsMappingService.class);
        bind(AdminAnalyticsService.class);
        var analytics = bind(AdminAnalyticsController.class);

        http().route(HTTPMethod.GET, "/api/admin/analytics/global", analytics::global);
        http().route(HTTPMethod.GET, "/api/admin/analytics/trend", analytics::trend);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-source", analytics::bySource);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-agent", analytics::byAgent);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-user", analytics::byUser);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-provider", analytics::byProvider);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-model", analytics::byModel);
        http().route(HTTPMethod.GET, "/api/admin/analytics/:dimension/trend", analytics::dimensionTrend);
    }

    private void bindWebService() {
        var chatSessionController = bind(ChatSessionController.class);
        http().route(HTTPMethod.GET, "/api/chat/sessions", chatSessionController::list);
        http().route(HTTPMethod.GET, "/api/chat/sessions/:sessionId", chatSessionController::get);
        http().route(HTTPMethod.DELETE, "/api/chat/sessions/:sessionId", chatSessionController::delete);
        http().route(HTTPMethod.POST, "/api/chat/sessions/batch-delete", chatSessionController::batchDelete);
        http().route(HTTPMethod.PUT, "/api/chat/sessions/:sessionId", chatSessionController::update);
        http().route(HTTPMethod.POST, "/api/chat/sessions/:sessionId/feedback", chatSessionController::submitFeedback);

        configureSpeechToken();
        api().service(SystemSettingsWebService.class, bind(SystemSettingsWebServiceImpl.class));
        api().service(AuthWebService.class, bind(AuthWebServiceImpl.class));
        api().service(UserWebService.class, bind(UserWebServiceImpl.class));
        api().service(ToolRegistryWebService.class, bind(ToolRegistryWebServiceImpl.class));
        api().service(NotificationWebService.class, bind(NotificationWebServiceImpl.class));
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
        api().service(TriggerWebService.class, bind(TriggerWebServiceImpl.class));
        api().service(DatasetWebService.class, bind(DatasetWebServiceImpl.class));
        api().service(ArtifactWebService.class, bind(ArtifactWebServiceImpl.class));
        registerA2A();
    }

    private void configureSpeechToken() {
        var speechController = bind(SpeechController.class);
        speechController.speechKey = property("azure.speech.key").orElse(null);
        speechController.speechRegion = property("azure.speech.region").orElse("eastus");
        speechController.speechEndpoint = property("azure.speech.endpoint").orElse(null);
        http().route(HTTPMethod.GET, "/api/speech/token", speechController::getToken);
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

    private void registerSkill() {
        bind(SkillService.class);
        bind(MarketplaceService.class);
        bind(MongoSkillProvider.class);
        bind(new SkillArchiveBuilder());
        bind(ai.core.server.skill.SkillToolAssembler.class);
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

    private void registerCapabilities() {
        var controller = bind(CapabilitiesController.class);
        controller.authDisabled = "true".equals(property("sys.auth.disabled").orElse("false"));
        http().route(HTTPMethod.GET, "/api/capabilities", controller::get);
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
