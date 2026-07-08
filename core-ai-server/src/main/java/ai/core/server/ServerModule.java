package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.api.server.ArtifactWebService;
import ai.core.api.server.auth.AuthWebService;
import ai.core.api.server.DatasetWebService;
import ai.core.api.server.FileWebService;
import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.workflow.WorkflowWebService;
import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.SkillWebService;
import ai.core.api.server.ToolRegistryWebService;
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
import ai.core.server.auth.AuthService;
import ai.core.server.blob.AzureBlobSasService;
import ai.core.server.blob.AzureObjectStorageService;
import ai.core.server.blob.BlobUploadCredentialController;
import ai.core.server.blob.MinioObjectStorageService;
import ai.core.server.blob.ObjectStorageService;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.server.agentbuilder.AgentBuilderTools;
import ai.core.server.llmcall.LLMCallBuilderTools;
import ai.core.server.web.sse.LiteLLMProxyChannelListener;
import ai.core.server.web.sse.SseAuthInterceptor;
import ai.core.server.web.CorsInterceptor;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileUploadController;
import ai.core.server.file.SharedFileDownloadController;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.telemetry.LLMTracer;
import ai.core.server.gateway.GatewayChatCompletionsChannelListener;
import ai.core.server.gateway.GatewayChatCompletionsSseEvent;
import ai.core.server.gateway.GatewayLLMProvider;
import ai.core.server.gateway.GatewayProxyController;
import ai.core.server.gateway.GatewayProxyService;
import ai.core.server.gateway.GatewayResponsesChannelListener;
import ai.core.server.gateway.GatewayResponsesSseEvent;
import ai.core.server.gateway.GatewayModelController;
import ai.core.server.gateway.GatewayModelDiscoveryService;
import ai.core.server.gateway.GatewayModelService;
import ai.core.server.gateway.GatewayProviderController;
import ai.core.server.gateway.GatewayProviderService;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.GatewaySecretProtector;
import ai.core.server.github.GitHubInstallationTokenService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.migration.SchemaMigrationManager;
import ai.core.server.memory.AgentMemoryConsolidationJob;
import ai.core.server.memory.AgentMemoryController;
import ai.core.server.memory.AgentMemoryService;
import ai.core.server.memory.AgentMemoryView;
import ai.core.server.memory.ListAgentMemoriesResponse;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.run.AgentRunService;
import ai.core.server.run.LLMCallExecutor;
import ai.core.server.run.AgentRunner;
import ai.core.server.workflow.AgentRunGateway;
import ai.core.server.workflow.MongoAgentRunGateway;
import ai.core.server.workflow.MongoWorkflowGraphLoader;
import ai.core.server.workflow.MongoWorkflowRunGateway;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeExecutorRegistry;
import ai.core.server.workflow.NodeType;
import ai.core.server.workflow.WorkflowDefinitionService;
import ai.core.server.workflow.WorkflowGraphLoader;
import ai.core.server.workflow.WorkflowPortService;
import ai.core.server.workflow.WorkflowPublishService;
import ai.core.server.workflow.WorkflowRunService;
import ai.core.server.workflow.WorkflowRunGateway;
import ai.core.server.workflow.WorkflowRunner;
import ai.core.server.workflow.WorkflowRunnerJob;
import ai.core.server.workflow.executor.AgentExecutor;
import ai.core.server.workflow.executor.AggregatorExecutor;
import ai.core.server.workflow.executor.ApiToolExecutor;
import ai.core.server.workflow.executor.CodeExecutor;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.HttpExecutor;
import ai.core.server.workflow.executor.HumanInputExecutor;
import ai.core.server.workflow.executor.IfElseExecutor;
import ai.core.server.workflow.executor.McpToolExecutor;
import ai.core.server.workflow.executor.RetryingNodeExecutor;
import ai.core.server.workflow.executor.StartExecutor;
import ai.core.server.workflow.executor.TemplateExecutor;
import ai.core.server.workflow.executor.WorkflowExecutor;

import java.util.Map;
import ai.core.server.schedule.AgentScheduleService;
import ai.core.server.schedule.AgentScheduler;
import ai.core.server.schedule.AgentSchedulerJob;
import ai.core.server.schedule.IdleSessionCleanupJob;
import ai.core.server.schedule.ToolRegistrySyncJob;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotCleanupJob;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
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
import ai.core.server.channel.ChannelAdminController;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelController;
import ai.core.server.channel.ChannelDispatcher;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.channel.slack.SlackInboundAdapter;
import ai.core.server.channel.slack.SlackOutboundAdapter;
import ai.core.server.channel.telegram.TelegramInboundAdapter;
import ai.core.server.channel.telegram.TelegramOutboundAdapter;
import ai.core.server.channel.weclaw.WeClawInboundAdapter;
import ai.core.server.channel.weclaw.WeClawOutboundAdapter;
import ai.core.server.channel.ChannelSyncController;
import ai.core.server.channel.openclaw.OcgCallbackPool;
import ai.core.server.channel.openclaw.OcgConfigController;
import ai.core.server.channel.openclaw.OcgConfigStore;
import ai.core.server.channel.openclaw.OcgHealthCheckJob;
import ai.core.server.channel.openclaw.OcgSandboxService;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
import ai.core.server.web.ArtifactWebServiceImpl;
import ai.core.server.web.ChatSessionController;
import ai.core.server.web.SessionCreateHelper;
import ai.core.server.web.DatasetWebServiceImpl;
import ai.core.server.web.SkillWebServiceImpl;
import ai.core.server.web.AgentRunWebServiceImpl;
import ai.core.server.web.WorkflowWebServiceImpl;
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
import ai.core.server.web.SystemSettingsWebServiceImpl;
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
import ai.core.server.trace.maintenance.TraceDailyMaintenanceJob;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceService;
import ai.core.server.trace.maintenance.TraceDailyMaintenanceTask;
import ai.core.server.task.TaskController;
import ai.core.server.task.TaskRunner;
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
    private SandboxService sandboxService;
    private ToolRegistryService toolRegistryService;   // shared with bindWorkflow() for the tool node executors
    private FileService fileService;                   // shared with bindWorkflow() (CODE staging) and sandbox staging

    @Override
    protected void initialize() {
        var migrationManager = bind(SchemaMigrationManager.class);
        onStartup(migrationManager::migrate);
        // Must be bound before bindService(): AgentSessionManager @Injects SandboxSnapshotService.
        bind(SandboxSnapshotService.class);
        bind(RequestAuthenticator.class);
        bind(ChannelConfigStore.class);         // must be before AuthInterceptor — AuthInterceptor checks per-channel auth
        bind(OcgConfigStore.class);
        http().intercept(bind(AuthInterceptor.class));
        var corsInterceptor = bind(CorsInterceptor.class);
        http().intercept(corsInterceptor);
        http().errorHandler(corsInterceptor);
        var toolRegistry = bind(ToolRegistryService.class);
        this.toolRegistryService = toolRegistry;
        registerFile();
        registerSkill();
        site().session().timeout(Duration.ofHours(24));
        site().session().cookie("CoreAIServerSessionId", null);
        SandboxModule sandboxModule = new SandboxModule();
        load(sandboxModule);
        this.sandboxService = sandboxModule.sandboxService;
        if (sandboxService != null) {
            sandboxService.setFileService(fileService);   // workflow artifact staging pulls bytes from FileRecord
            toolRegistry.setSandboxService(sandboxService);
        }

        // ChannelRegistry and adapters must be bound before bindService() because
        // AgentSessionManager injects ChannelRegistry for bridge cleanup.
        bindChannelRegistry();

        // SystemSettingsService must be bound before bindService() because LLMCallExecutor and AgentRunner inject it
        bind(SystemSettingsService.class);

        var taskRunner = bind(TaskRunner.class);   // must be before bindService() — TaskController injects it

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

        // ChannelDispatcher and ChannelController must be bound after MessagingModule
        // because ChannelDispatcher injects CommandPublisher.
        bindChannels();

        bind(PodLocalExecutor.class);
        bindWebService();
        schedule().fixedRate("agent-scheduler", bind(AgentSchedulerJob.class), Duration.ofMinutes(1));
        schedule().fixedRate("tool-registry-sync", bind(ToolRegistrySyncJob.class), Duration.ofSeconds(30));
        schedule().fixedRate("idle-session-cleanup", bind(IdleSessionCleanupJob.class), Duration.ofMinutes(5));
        schedule().fixedRate("sandbox-snapshot-cleanup", bind(SandboxSnapshotCleanupJob.class), Duration.ofHours(1));
        schedule().fixedRate("ocg-health-check", bind(OcgHealthCheckJob.class), Duration.ofMinutes(1));
        var memoryConsolidationJob = bind(AgentMemoryConsolidationJob.class);
        memoryConsolidationJob.extractionModel = property("agent.memory.extraction.model")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(AgentMemoryConsolidationJob.DEFAULT_EXTRACTION_MODEL);
        var systemSettingsService = bean(SystemSettingsService.class);
        systemSettingsService.defaultMemoryExtractionModel = memoryConsolidationJob.extractionModel;
        systemSettingsService.defaultLlmModel = property("llm.model")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(null);
        systemSettingsService.defaultLlmMultiModalModel = property("llm.model.multimodal")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(null);
        schedule().fixedRate("agent-memory-consolidation", memoryConsolidationJob, Duration.ofHours(1));
        bind(TraceDailyMaintenanceService.class);
        var traceDailyMaintenanceTask = bind(TraceDailyMaintenanceTask.class);
        onStartup(() -> taskRunner.register(traceDailyMaintenanceTask));
        schedule().fixedRate("trace-daily-maintenance", bind(TraceDailyMaintenanceJob.class), Duration.ofHours(1));
        registerTrace();
        registerSystemPrompt();
        registerCapabilities();
        registerForYou();
        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.intercept(new SseAuthInterceptor(bean(RequestAuthenticator.class)));
        sseConfig.listen(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class));
        sseConfig.listen(HTTPMethod.POST, "/api/a2a" + A2AHttpPaths.MESSAGE_STREAM, StreamResponse.class, bind(A2AStreamChannelListener.class));
        sseConfig.listen(HTTPMethod.POST, "/api/litellm/v1/chat/completions", Object.class, bind(LiteLLMProxyChannelListener.class));
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
        bind(AgentMemoryService.class);
        bind(LLMCallExecutor.class);
        bind(DatasetService.class);
        bind(DatasetRecordService.class);
        bind(ai.core.server.agent.SubAgentAssembler.class);
        bind(AgentRunner.class);
        onShutdown(bean(AgentRunner.class)::shutdown);
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
        var ocgSandboxService = bind(OcgSandboxService.class);
        ocgSandboxService.publicUrl = publicUrl;
        onStartup(ocgSandboxService::recoverOnStartup);
        var ocgCallbackPool = bind(OcgCallbackPool.class);
        onShutdown(ocgCallbackPool::shutdown);
        bind(RunAgentAction.class);

        var memoryController = bind(AgentMemoryController.class);
        http().route(HTTPMethod.GET, "/api/agents/:id/memories", memoryController::list);

        bind(ForYouService.class);
        bind(ai.core.server.artifact.ArtifactService.class);
        bind(SessionCreateHelper.class);

        var taskController = bind(TaskController.class);
        http().route(HTTPMethod.GET, "/api/admin/tasks", taskController::list);
        http().route(HTTPMethod.PUT, "/api/admin/tasks/:taskId/retry", taskController::retry);
        http().route(HTTPMethod.POST, "/api/admin/tasks", taskController::run);

        bindWorkflow();
    }

    private void bindWorkflow() {
        // AGENT/LLM nodes run as decoupled child AgentRuns through this gateway (depends on AgentRunner above).
        var agentRunGateway = bind(MongoAgentRunGateway.class);
        bind(AgentRunGateway.class, agentRunGateway);
        var graphLoader = bind(MongoWorkflowGraphLoader.class);
        bind(WorkflowGraphLoader.class, graphLoader);

        // AGENT/LLM, the two tool nodes and HTTP get synchronous retry-on-transient-failure; START/END/IF_ELSE/CODE don't.
        var agentExecutor = new RetryingNodeExecutor(new AgentExecutor(agentRunGateway));
        var mcpToolExecutor = new RetryingNodeExecutor(new McpToolExecutor(toolRegistryService));
        var apiToolExecutor = new RetryingNodeExecutor(new ApiToolExecutor(toolRegistryService));
        var httpExecutor = new RetryingNodeExecutor(new HttpExecutor());
        var workflowRunGateway = bind(MongoWorkflowRunGateway.class);
        bind(WorkflowRunGateway.class, workflowRunGateway);
        var workflowExecutor = new WorkflowExecutor(workflowRunGateway, 5);   // depth cap 5 (design default)
        var registry = new NodeExecutorRegistry(Map.ofEntries(
            Map.entry(NodeType.START, new StartExecutor()),
            Map.entry(NodeType.END, new EndExecutor()),
            Map.entry(NodeType.AGENT, agentExecutor),
            Map.entry(NodeType.LLM, agentExecutor),
            Map.entry(NodeType.MCP_TOOL, mcpToolExecutor),
            Map.entry(NodeType.API_TOOL, apiToolExecutor),
            Map.entry(NodeType.HTTP, httpExecutor),
            Map.entry(NodeType.IF_ELSE, new IfElseExecutor()),
            Map.entry(NodeType.AGGREGATOR, new AggregatorExecutor()),
            Map.entry(NodeType.TEMPLATE, new TemplateExecutor()),
            Map.entry(NodeType.HUMAN_INPUT, new HumanInputExecutor()),
            Map.entry(NodeType.WORKFLOW, workflowExecutor),
            Map.entry(NodeType.CODE, new CodeExecutor(sandboxService, fileService))));
        bind(NodeExecutor.class, registry);

        // WorkflowDefinitionService must be bound before WorkflowPortService, which injects it
        bind(WorkflowDefinitionService.class);
        bind(WorkflowPublishService.class);
        bind(WorkflowPortService.class);
        bind(WorkflowRunner.class);
        bind(WorkflowRunService.class);
        // Pure recovery/fallback: every creation path submits immediately, so this only catches hard crashes
        // (OOM/SIGKILL/node loss) that leave a stale lease — a slow tick is enough. (No shutdown-time DB hand-off:
        // a Mongo write that late in teardown fails when the codec registry is already gone.)
        schedule().fixedRate("workflow-runner", bind(WorkflowRunnerJob.class), Duration.ofSeconds(60));
    }

    private void bindWebService() {
        var chatSessionController = bind(ChatSessionController.class);
        http().route(HTTPMethod.GET, "/api/chat/sessions", chatSessionController::list);
        http().route(HTTPMethod.GET, "/api/chat/sessions/:sessionId", chatSessionController::get);
        http().route(HTTPMethod.DELETE, "/api/chat/sessions/:sessionId", chatSessionController::delete);
        http().route(HTTPMethod.POST, "/api/chat/sessions/batch-delete", chatSessionController::batchDelete);
        http().route(HTTPMethod.PUT, "/api/chat/sessions/:sessionId", chatSessionController::update);

        // Speech token exchange for browser-side Azure Speech SDK
        var speechController = bind(SpeechController.class);
        speechController.speechKey = property("azure.speech.key").orElse(null);
        speechController.speechRegion = property("azure.speech.region").orElse("eastus");
        speechController.speechEndpoint = property("azure.speech.endpoint").orElse(null);
        http().route(HTTPMethod.GET, "/api/speech/token", speechController::getToken);

        // Object storage upload credential for direct browser-to-storage uploads.
        // Set sys.storage.provider to "azure" or "minio" to force a provider,
        // or leave empty to auto-detect from available credentials.
        var blobController = bind(BlobUploadCredentialController.class);

        // Read all properties unconditionally to satisfy core-ng property validator.
        // The provider-specific ones are only used when that provider is active.
        var azureAccountName = property("azure.blob.account.name").orElse(null);
        var azureAccountKey = property("azure.blob.account.key").orElse(null);
        var azureMultimodalContainer = property("azure.blob.multimodal.container").orElse("uploads");
        var azureSandboxContainer = property("azure.blob.sandbox.container").orElse("sandbox-uploads");
        var azurePublicBaseUrl = property("azure.blob.public.base.url").orElse(null);

        var minioEndpoint = property("storage.minio.endpoint").orElse(null);
        var minioAccessKey = property("storage.minio.access.key").orElse(null);
        var minioSecretKey = property("storage.minio.secret.key").orElse(null);
        var minioRegion = property("storage.minio.region").orElse("us-east-1");
        var minioMultimodalBucket = property("storage.minio.multimodal.bucket").orElse("uploads");
        var minioSandboxBucket = property("storage.minio.sandbox.bucket").orElse("sandbox-uploads");
        var minioPublicBaseUrl = property("storage.minio.public.base.url").orElse(null);

        var provider = property("sys.storage.provider").orElse("");
        ObjectStorageService objectStorage = null;

        if (provider.isEmpty() || "azure".equals(provider)) {
            var sasService = AzureBlobSasService.tryCreate(azureAccountName, azureAccountKey);
            if (sasService != null) {
                blobController.multimodalContainer = azureMultimodalContainer;
                blobController.sandboxContainer = azureSandboxContainer;
                objectStorage = new AzureObjectStorageService(sasService, azurePublicBaseUrl);
                LOGGER.info("Object storage configured: provider=azure, multimodal={}, sandbox={}",
                        azureMultimodalContainer, azureSandboxContainer);
            }
        }
        if (objectStorage == null && (provider.isEmpty() || "minio".equals(provider))) {
            if (minioEndpoint != null && !minioEndpoint.isBlank() && minioAccessKey != null && !minioAccessKey.isBlank()) {
                blobController.multimodalContainer = minioMultimodalBucket;
                blobController.sandboxContainer = minioSandboxBucket;
                objectStorage = new MinioObjectStorageService(minioEndpoint, minioRegion, minioAccessKey, minioSecretKey, minioPublicBaseUrl);
                LOGGER.info("Object storage configured: provider=minio, endpoint={}, multimodal={}, sandbox={}",
                        minioEndpoint, minioMultimodalBucket, minioSandboxBucket);
            }
        }

        if (objectStorage != null) {
            blobController.storageService = objectStorage;
            if (sandboxService != null) {
                sandboxService.setStorageService(objectStorage);
            }
        }

        // Sandbox filesystem snapshot: kill-switched by sys.sandbox.snapshot.enabled (default false).
        // With no object storage configured, configure() forces disabled regardless of the flag.
        var snapshotEnabled = "true".equalsIgnoreCase(property("sys.sandbox.snapshot.enabled").orElse("false"));
        var snapshotContainer = property("azure.blob.snapshot.container").orElse("sandbox-snapshots");
        var minioSnapshotBucket = property("storage.minio.snapshot.bucket").orElse("sandbox-snapshots");
        if (objectStorage instanceof MinioObjectStorageService) snapshotContainer = minioSnapshotBucket;
        var snapshotService = bean(SandboxSnapshotService.class);
        snapshotService.configure(objectStorage, snapshotContainer, snapshotEnabled && objectStorage != null);
        if (sandboxService != null) {
            sandboxService.setSnapshotService(snapshotService);
        }

        http().bean(BlobUploadCredentialView.class);
        http().bean(AgentMemoryView.class);
        http().bean(ListAgentMemoriesResponse.class);
        http().route(HTTPMethod.GET, "/api/blob/upload-credential", blobController::getCredential);

        // prefer a dedicated gateway.secret.key; keep the mongo-uri-derived key as decrypt-only
        // fallback so secrets encrypted before configuring the dedicated key stay readable
        var gatewaySecretKey = property("gateway.secret.key").map(String::trim).filter(key -> !key.isBlank()).orElse(null);
        var gatewayLegacySecret = requiredProperty("sys.mongo.uri");
        var gatewaySecretProtector = bind(gatewaySecretKey == null ? new GatewaySecretProtector(gatewayLegacySecret) : new GatewaySecretProtector(gatewaySecretKey, gatewayLegacySecret));
        var gatewayRoutingEngine = bind(GatewayRoutingEngine.class);
        bind(GatewayModelDiscoveryService.class);
        bind(GatewayModelService.class);
        bind(GatewayProviderService.class);
        bind(GatewayProxyService.class);
        var gatewayProviderController = bind(GatewayProviderController.class);
        http().route(HTTPMethod.GET, "/api/gateway/providers", gatewayProviderController::list);
        http().route(HTTPMethod.POST, "/api/gateway/providers", gatewayProviderController::create);
        http().route(HTTPMethod.PUT, "/api/gateway/providers/:id", gatewayProviderController::update);
        http().route(HTTPMethod.DELETE, "/api/gateway/providers/:id", gatewayProviderController::delete);
        http().route(HTTPMethod.POST, "/api/gateway/providers/:id/test", gatewayProviderController::test);
        var gatewayModelController = bind(GatewayModelController.class);
        http().route(HTTPMethod.GET, "/api/gateway/models", gatewayModelController::list);
        http().route(HTTPMethod.GET, "/api/gateway/models/available", gatewayModelController::listAvailable);
        http().route(HTTPMethod.POST, "/api/gateway/models", gatewayModelController::create);
        http().route(HTTPMethod.PUT, "/api/gateway/models/:id", gatewayModelController::update);
        http().route(HTTPMethod.DELETE, "/api/gateway/models/:id", gatewayModelController::delete);
        http().route(HTTPMethod.POST, "/api/gateway/providers/:id/models/discover", gatewayModelController::discover);
        http().route(HTTPMethod.POST, "/api/gateway/providers/:id/models/import", gatewayModelController::importModels);
        var gatewayProxyController = bind(GatewayProxyController.class);
        http().route(HTTPMethod.GET, "/api/gateway/v1/models", gatewayProxyController::models);
        http().route(HTTPMethod.POST, "/api/gateway/v1/chat/completions", gatewayProxyController::chatCompletions);
        http().route(HTTPMethod.POST, "/api/gateway/v1/responses", gatewayProxyController::responses);
        // streaming requests (Accept: text/event-stream) are served incrementally over SSE;
        // others fall through to the buffered http routes above
        var gatewaySse = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        gatewaySse.listen(HTTPMethod.POST, "/api/gateway/v1/chat/completions", GatewayChatCompletionsSseEvent.class, bind(GatewayChatCompletionsChannelListener.class));
        gatewaySse.requireEventStreamAccept(HTTPMethod.POST, "/api/gateway/v1/chat/completions");
        gatewaySse.listen(HTTPMethod.POST, "/api/gateway/v1/responses", GatewayResponsesSseEvent.class, bind(GatewayResponsesChannelListener.class));
        gatewaySse.requireEventStreamAccept(HTTPMethod.POST, "/api/gateway/v1/responses");
        // bridge agent runtime LLM calls onto gateway-managed providers; static property-based providers remain the fallback
        var llmProviders = bean(LLMProviders.class);
        var fallbackLLMProvider = llmProviders.getProviderTypes().isEmpty() ? null : llmProviders.getProvider();
        var gatewayLLMConfig = fallbackLLMProvider == null ? new LLMProviderConfig(null, null, null) : new LLMProviderConfig(fallbackLLMProvider.config);
        var gatewayLLMProvider = new GatewayLLMProvider(gatewayLLMConfig, gatewayRoutingEngine, gatewaySecretProtector, fallbackLLMProvider);
        if (fallbackLLMProvider != null) {
            gatewayLLMProvider.setTracer(fallbackLLMProvider.getTracer());
        } else {
            try {
                // gateway-only deployment: pick up the telemetry tracer directly so gateway-routed calls stay traced
                gatewayLLMProvider.setTracer(bean(LLMTracer.class));
            } catch (Error e) {
                LOGGER.info("no LLMTracer configured, gateway LLM calls run untraced");
            }
        }
        bind(gatewayLLMProvider);
        llmProviders.addProvider(LLMProviderType.GATEWAY, gatewayLLMProvider);
        llmProviders.setDefaultProvider(LLMProviderType.GATEWAY);

        api().service(SystemSettingsWebService.class, bind(SystemSettingsWebServiceImpl.class));
        api().service(AuthWebService.class, bind(AuthWebServiceImpl.class));
        api().service(UserWebService.class, bind(UserWebServiceImpl.class));
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        api().service(ToolRegistryWebService.class, bind(ToolRegistryWebServiceImpl.class));
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
        api().service(AgentRunWebService.class, bind(AgentRunWebServiceImpl.class));
        api().service(WorkflowWebService.class, bind(WorkflowWebServiceImpl.class));
        api().service(AgentScheduleWebService.class, bind(AgentScheduleWebServiceImpl.class));
        api().service(TriggerWebService.class, bind(TriggerWebServiceImpl.class));
        api().service(DatasetWebService.class, bind(DatasetWebServiceImpl.class));
        api().service(ArtifactWebService.class, bind(ArtifactWebServiceImpl.class));
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
            "/", "/login", "/register", "/authorize", "/chat", "/agents", "/sessions",
            "/system-prompts", "/dashboard", "/traces", "/skills",
            "/prompts", "/scheduler", "/tasks", "/tools", "/api-tools",
            "/triggers", "/datasets", "/for-you", "/for-you/artifacts", "/workflows", "/workflows/explore", "/report-issue"
        };
        for (var path : spaRoutes) {
            http().route(HTTPMethod.GET, path, controller::serve);
        }
        http().route(HTTPMethod.GET, "/agents/:id", controller::serve);
        http().route(HTTPMethod.GET, "/agents/:id/memories", controller::serve);
        http().route(HTTPMethod.GET, "/workflows/:id", controller::serve);
        http().route(HTTPMethod.GET, "/workflows/:id/runs", controller::serve);
        http().route(HTTPMethod.GET, "/runs/:id", controller::serve);
        // note: /mcp is NOT an SPA route — core-ai's own MCP server (McpServerModule) owns GET /mcp. The frontend
        // MCP page only works via in-app navigation; serving it on refresh needs a non-clashing path (e.g. /mcp-servers).
        // /mcp/:id does NOT conflict with McpServerModule (which only owns GET /mcp), so it can be a SPA fallback route.
        http().route(HTTPMethod.GET, "/mcp/:id", controller::serve);
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
        http().route(HTTPMethod.GET, "/triggers/channels", controller::serve);
        http().route(HTTPMethod.GET, "/triggers/openclaw", controller::serve);
        http().route(HTTPMethod.GET, "/tools/builtin", controller::serve);
        http().route(HTTPMethod.GET, "/settings", controller::serve);
        http().route(HTTPMethod.GET, "/settings/users", controller::serve);
        http().route(HTTPMethod.GET, "/settings/api-keys", controller::serve);
        http().route(HTTPMethod.GET, "/settings/gateway", controller::serve);
        http().route(HTTPMethod.GET, "/settings/system", controller::serve);
    }
    private void registerFile() {
        this.fileService = bind(FileService.class);
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));
        http().route(HTTPMethod.GET, "/api/public/artifacts/:token/content", bind(SharedFileDownloadController.class));
    }
    private void registerSkill() {
        bind(SkillService.class);
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
    private void registerWebhookTrigger() {
        var controller = bind(TriggerController.class);
        http().route(HTTPMethod.POST, "/api/webhook-triggers/:id", controller);
        // GET for Slack URL verification challenge
        http().route(HTTPMethod.GET, "/api/webhook-triggers/:id", controller);
    }

    private void bindChannelRegistry() {
        var registry = bind(ChannelRegistry.class);

        // Register channel adapters — each pair handles inbound verification/parsing
        // and outbound message delivery for a specific platform.
        var slackInbound = new SlackInboundAdapter();
        var slackOutbound = new SlackOutboundAdapter();
        registry.register(slackInbound, slackOutbound);

        var telegramInbound = new TelegramInboundAdapter();
        var telegramOutbound = new TelegramOutboundAdapter();
        registry.register(telegramInbound, telegramOutbound);

        var weclawInbound = new WeClawInboundAdapter();
        var weclawOutbound = new WeClawOutboundAdapter();
        registry.register(weclawInbound, weclawOutbound);
    }

    private void bindChannels() {
        bind(ChannelDispatcher.class);

        // Unified webhook endpoint for all channels
        var channelController = bind(ChannelController.class);
        http().route(HTTPMethod.POST, "/api/channels/:channelId", channelController);
        http().route(HTTPMethod.GET, "/api/channels/:channelId", channelController);

        // Channel admin CRUD endpoints
        var channelAdmin = bind(ChannelAdminController.class);

        // OpenAI-compatible sync endpoint for all channels
        var channelSync = bind(ChannelSyncController.class);
        http().route(HTTPMethod.POST, "/api/channels/:channelId/v1/chat/completions", channelSync);

        var ocgConfigController = bind(OcgConfigController.class);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs", ocgConfigController::list);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs", ocgConfigController::create);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs/:id", ocgConfigController::get);
        http().route(HTTPMethod.PUT, "/api/admin/ocg-configs/:id", ocgConfigController::update);
        http().route(HTTPMethod.DELETE, "/api/admin/ocg-configs/:id", ocgConfigController::delete);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/start", ocgConfigController::start);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/stop", ocgConfigController::stop);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/restart", ocgConfigController::restart);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/command", ocgConfigController::command);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs/:id/logs", ocgConfigController::logs);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs/:id/status", ocgConfigController::status);

        http().route(HTTPMethod.GET, "/api/admin/channels", channelAdmin::list);
        http().route(HTTPMethod.POST, "/api/admin/channels", channelAdmin::create);
        http().route(HTTPMethod.GET, "/api/admin/channels/:channelId", channelAdmin::get);
        http().route(HTTPMethod.PUT, "/api/admin/channels/:channelId", channelAdmin::update);
        http().route(HTTPMethod.DELETE, "/api/admin/channels/:channelId", channelAdmin::delete);
        http().route(HTTPMethod.GET, "/api/admin/channel-types", channelAdmin::types);
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
