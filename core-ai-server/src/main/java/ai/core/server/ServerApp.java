package ai.core.server;

import ai.core.McpServerModule;
import ai.core.MultiAgentModule;
import ai.core.server.apimcp.mcp.McpModule;
import ai.core.server.apimcp.serviceapi.ServiceApiModule;
import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.openclaw.OcgConfigView;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.BackgroundTask;
import ai.core.server.memory.AgentMemory;
import ai.core.server.memory.AgentMemoryExtractionCursor;
import ai.core.server.memory.experiment.AgentMemoryExperimentConfig;
import ai.core.server.memory.experiment.AgentMemoryExperimentRun;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.SessionFeedback;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.Notification;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.MarketplaceRepo;
import ai.core.server.domain.SystemPrompt;
import ai.core.server.domain.SystemSettings;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.User;
import ai.core.server.domain.UserReport;
import ai.core.server.domain.UserTodo;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.migration.SchemaVersion;
import ai.core.server.sandbox.snapshot.SandboxEpochDoc;
import ai.core.server.sandbox.snapshot.SandboxSnapshotDoc;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.trace.domain.AnalyticsDailyStats;
import ai.core.server.trace.domain.PromptTemplate;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import ai.core.server.trace.domain.TraceFacetRow;
import ai.core.server.trigger.domain.Trigger;
import ai.core.server.web.sse.ChannelService;
import ai.core.server.web.sse.SessionChannelService;
import core.framework.module.App;
import core.framework.module.SystemModule;
import core.framework.mongo.module.MongoConfig;

/**
 * @author stephen
 */
public class ServerApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        loadProperties("agent.properties");

        // Pass mcp.servers.json to system properties for ToolRegistryService
        property("mcp.servers.json").ifPresent(json -> System.setProperty("mcp.servers.json", json));

        registerMongo();

        load(new MultiAgentModule());
        load(new ServiceApiModule());
        load(new McpServerModule());
        load(new McpModule());

        bindServices();

        load(new ObjectStorageModule());
        load(new GatewayModule());
        load(new TraceModule());
        load(new MemoryModule());
        load(new SandboxModule());
        load(new ServerModule());
        load(new SessionModule());
        load(new AgentRunnerModule());
        load(new MessagingModule());
        load(new ChannelModule());
        load(new WorkflowModule());
        load(new WebModule());
    }

    private void bindServices() {
        bind(ChannelService.class);
        bind(SessionChannelService.class);
        bind(SystemSettingsService.class);
        bind(SandboxSnapshotService.class);
    }

    private void registerMongo() {
        var mongo = config(MongoConfig.class);
        mongo.uri(requiredProperty("sys.mongo.uri"));

        registerCoreCollections(mongo);

        mongo.collection(SystemPrompt.class);
        mongo.collection(SystemSettings.class);
        mongo.collection(ChatMessage.class);
        mongo.collection(ChatSession.class);
        mongo.collection(SessionFeedback.class);

        mongo.collection(Trace.class);
        mongo.collection(Span.class);
        mongo.view(TraceFacetRow.class);
        mongo.collection(PromptTemplate.class);

        mongo.collection(ServiceApi.class);
        mongo.collection(Trigger.class);

        mongo.collection(Dataset.class);
        mongo.collection(DatasetRecord.class);

        mongo.collection(UserReport.class);
        mongo.collection(UserTodo.class);

        registerWorkflowCollections(mongo);

        mongo.collection(AgentMemory.class);
        mongo.collection(AgentMemoryExtractionCursor.class);

        mongo.collection(AgentMemoryExperimentConfig.class);
        mongo.collection(AgentMemoryExperimentRun.class);

        mongo.collection(SandboxSnapshotDoc.class);
        mongo.collection(SandboxEpochDoc.class);

        mongo.collection(BackgroundTask.class);
        mongo.collection(TraceDailyStats.class);
        mongo.collection(AnalyticsDailyStats.class);

        mongo.collection(Notification.class);
    }

    private void registerCoreCollections(MongoConfig mongo) {
        mongo.collection(User.class);
        mongo.collection(ToolRegistryEntry.class);
        mongo.collection(AgentDefinition.class);
        mongo.collection(ChannelConfigView.class);
        mongo.collection(OcgConfigView.class);
        mongo.collection(AgentSchedule.class);
        mongo.collection(AgentRun.class);
        mongo.collection(FileRecord.class);
        mongo.collection(GatewayModelConfig.class);
        mongo.collection(GatewayProviderConfig.class);
        mongo.collection(SkillDefinition.class);
        mongo.collection(MarketplaceRepo.class);
        mongo.collection(SchemaVersion.class);
    }

    private void registerWorkflowCollections(MongoConfig mongo) {
        mongo.collection(WorkflowDefinition.class);
        mongo.collection(WorkflowPublishedVersion.class);
        mongo.collection(WorkflowRun.class);
        mongo.collection(WorkflowNodeRun.class);
        mongo.view(ArtifactRef.class);
    }
}
