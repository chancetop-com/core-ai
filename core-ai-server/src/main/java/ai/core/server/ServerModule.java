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
import ai.core.sandbox.SandboxProvider;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.agent.JavaToSchemaService;
import ai.core.server.auth.AuthService;
import ai.core.server.llmcall.LLMCallBuilderTools;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.EventSubscriber;
import ai.core.server.messaging.InProcessCommandHandler;
import ai.core.server.messaging.JedisConfig;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.TokenResolver;
import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxExtensionsClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProvider;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProviderConfig;
import ai.core.server.sandbox.docker.DockerSandboxProvider;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import ai.core.server.sandbox.kubernetes.KubernetesSandboxProvider;
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
import ai.core.server.session.ChatMessageService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillUploadController;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.user.UserService;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
import ai.core.server.web.ChatSessionController;
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
import ai.core.server.web.CapabilitiesController;
import ai.core.server.web.StaticFileController;
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

        site().session().timeout(Duration.ofHours(24));
        site().session().cookie("CoreAIServerSessionId", null);

        bindSandboxService();
        bindService();
        bindAuthService();

        var builderTools = bind(LLMCallBuilderTools.class);

        var mcpConfig = property("mcp.servers.json").orElse(null);
        onStartup(() -> toolRegistry.initialize(mcpConfig));
        onStartup(builderTools::initialize);

        registerMessaging();

        bindWebService();
        http().route(HTTPMethod.POST, "/api/webhooks/:agentId", bind(WebhookController.class));

        schedule().fixedRate("agent-scheduler", bind(AgentSchedulerJob.class), Duration.ofMinutes(1));

        registerTrace();
        registerSystemPrompt();
        registerCapabilities();

        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.listen(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class));

        registerStaticFiles();
    }

    private void registerMessaging() {
        var redisHost = property("sys.jedis.host").orElse("localhost");
        var redisPort = Integer.parseInt(property("sys.jedis.port").orElse("6379"));

        var redisConfig = new JedisConfig(redisHost, redisPort);
        var jedisPool = redisConfig.createJedisPool();
        onShutdown(jedisPool::close);

        // Publishers
        bind(new CommandPublisher(jedisPool));
        bind(new EventPublisher(jedisPool));

        // Wire EventPublisher into the already-bound AgentSessionManager
        bean(AgentSessionManager.class).setEventPublisher(bean(EventPublisher.class));

        // Subscribers (combined mode: consume events and commands in the same JVM)
        var eventSubscriber = new EventSubscriber(jedisPool, bean(SessionChannelService.class));
        onStartup(eventSubscriber::start);
        onShutdown(eventSubscriber::stop);

        var chatMessageService = bean(ChatMessageService.class);
        var commandHandler = new InProcessCommandHandler(jedisPool, bean(AgentSessionManager.class), chatMessageService);
        onStartup(commandHandler::start);
        onShutdown(commandHandler::stop);
    }

    private void bindAuthService() {
        var authService = bind(AuthService.class);
        authService.adminEmail = property("sys.admin.email").orElse("admin@example.com");
        authService.adminPassword = property("sys.admin.password").orElse("admin");
        authService.adminName = property("sys.admin.name").orElse("Admin");

        onStartup(authService::initialize);
    }

    private void bindSandboxService() {
        property("sys.sandbox.provider").ifPresent(p -> {
            SandboxProvider provider;
            if (p.equalsIgnoreCase("kubernetes")) {
                provider = createKubernetesSandboxProvider();
            } else if (p.equalsIgnoreCase("agent-sandbox")) {
                provider = createAgentSandboxProvider();
            } else if (p.equalsIgnoreCase("docker")) {
                var socketPath = property("sys.sandbox.docker.socket").orElse("unix:///var/run/docker.sock");
                var workspaceBase = Path.of(property("sys.sandbox.docker.workspace.base").orElse("/tmp/workspaces"));
                provider = new DockerSandboxProvider(socketPath, workspaceBase, null);
            } else {
                bind(new SandboxService());
                return;
            }
            var sandboxService = bind(new SandboxService(provider));
            onShutdown(sandboxService::shutdown);
        });
        if (property("sys.sandbox.provider").isEmpty()) {
            bind(new SandboxService());
        }
    }

    private SandboxProvider createKubernetesSandboxProvider() {
        var namespace = property("sys.sandbox.kubernetes.namespace").orElse("core-ai-sandbox");
        var token = property("sys.sandbox.kubernetes.token").orElse(null);
        KubernetesClient kubernetesClient;
        if (token != null && !token.isBlank()) {
            var apiServer = property("sys.sandbox.kubernetes.apiServer").orElse("https://kubernetes.default.svc");
            kubernetesClient = new KubernetesClient(apiServer, token, namespace, 60);
        } else {
            kubernetesClient = KubernetesClient.createInCluster(namespace, 60);
        }
        var useHostPort = property("sys.sandbox.kubernetes.hostPort").orElse("false").equalsIgnoreCase("true");
        return new KubernetesSandboxProvider(kubernetesClient, null, useHostPort);
    }

    private SandboxProvider createAgentSandboxProvider() {
        var namespace = property("sys.sandbox.kubernetes.namespace").orElse("core-ai-sandbox");
        var token = property("sys.sandbox.kubernetes.token").orElse(null);
        var apiServer = property("sys.sandbox.kubernetes.apiServer").orElse("https://kubernetes.default.svc");
        TokenResolver tokenResolver = (token != null && !token.isBlank())
                ? TokenResolver.fixed(token)
                : TokenResolver.inCluster();
        var client = new AgentSandboxClient(apiServer, namespace, tokenResolver, 120);
        var useHostPort = property("sys.sandbox.kubernetes.hostPort").orElse("false").equalsIgnoreCase("true");
        KubernetesClient kubernetesClient = null;
        if (useHostPort) {
            if (token != null && !token.isBlank()) {
                kubernetesClient = new KubernetesClient(apiServer, token, namespace, 60);
            } else {
                kubernetesClient = KubernetesClient.createInCluster(namespace, 60);
            }
        }
        // Warm pool mode: if template name is configured, use SandboxClaim via extensions API
        var templateName = property("sys.sandbox.agentSandbox.template").orElse(null);
        var warmPoolName = property("sys.sandbox.agentSandbox.warmPool").orElse("default");
        AgentSandboxExtensionsClient extensionsClient = null;
        if (templateName != null && !templateName.isBlank()) {
            extensionsClient = new AgentSandboxExtensionsClient(apiServer, namespace, tokenResolver, 120);
        }
        var config = new AgentSandboxProviderConfig();
        config.client = client;
        config.extensionsClient = extensionsClient;
        config.kubernetesClient = kubernetesClient;
        config.useHostPort = useHostPort;
        config.templateName = templateName;
        config.warmPoolName = warmPoolName;
        return new AgentSandboxProvider(config);
    }

    private void bindService() {
        bind(SystemPromptService.class);
        bind(LLMCallExecutor.class);
        bind(AgentRunner.class);
        bind(AgentScheduler.class);
        bind(ChannelService.class);
        bind(SessionChannelService.class);
        bind(ChatMessageService.class);
        bind(AgentSessionManager.class);
        bind(AgentDefinitionService.class);
        bind(JavaToSchemaService.class);
        bind(AgentDraftGenerator.class);
        bind(AgentRunService.class);
        bind(AgentScheduleService.class);
        bind(UserService.class);
    }

    private void bindWebService() {
        var chatSessionController = bind(ChatSessionController.class);
        http().route(HTTPMethod.GET, "/api/chat/sessions", chatSessionController::list);
        http().route(HTTPMethod.DELETE, "/api/chat/sessions/:sessionId", chatSessionController::delete);

        api().service(AuthWebService.class, bind(AuthWebServiceImpl.class));
        api().service(UserWebService.class, bind(UserWebServiceImpl.class));
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        api().service(ToolRegistryWebService.class, bind(ToolRegistryWebServiceImpl.class));
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
        api().service(AgentRunWebService.class, bind(AgentRunWebServiceImpl.class));
        api().service(AgentScheduleWebService.class, bind(AgentScheduleWebServiceImpl.class));
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
            "/system-prompts", "/dashboard", "/traces", "/skills",
            "/prompts", "/scheduler", "/tasks", "/tools", "/api-tools"
        };
        for (var path : spaRoutes) {
            http().route(HTTPMethod.GET, path, controller::serve);
        }
        // SPA dynamic routes
        http().route(HTTPMethod.GET, "/agents/:id", controller::serve);
        http().route(HTTPMethod.GET, "/runs/:id", controller::serve);
        http().route(HTTPMethod.GET, "/system-prompts/:id", controller::serve);
        http().route(HTTPMethod.GET, "/traces/:id", controller::serve);
        http().route(HTTPMethod.GET, "/skills/:id", controller::serve);
        http().route(HTTPMethod.GET, "/skills/:id/edit", controller::serve);
        http().route(HTTPMethod.GET, "/prompts/:id", controller::serve);
        http().route(HTTPMethod.GET, "/api-tools/:id", controller::serve);
    }

    private void registerFile() {
        var storagePath = property("sys.file.storagePath").orElse("./data/files");
        bind(new FileService(Path.of(storagePath)));
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));
    }

    private void registerSkill() {
        bind(SkillService.class);
        bind(MongoSkillProvider.class);
        bind(new SkillArchiveBuilder());
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
