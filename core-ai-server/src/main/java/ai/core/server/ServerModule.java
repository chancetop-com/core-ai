package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.api.server.FileWebService;
import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.UserWebService;
import ai.core.server.agent.AgentDefinitionWebServiceImpl;
import ai.core.server.auth.AuthInterceptor;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileUploadController;
import ai.core.server.file.FileWebServiceImpl;
import ai.core.server.migration.SchemaMigrationManager;
import ai.core.server.run.AgentRunner;
import ai.core.server.run.AgentRunWebServiceImpl;
import ai.core.server.schedule.AgentScheduler;
import ai.core.server.schedule.AgentSchedulerJob;
import ai.core.server.schedule.AgentScheduleWebServiceImpl;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.tool.ToolRegistryWebServiceImpl;
import ai.core.server.user.UserWebServiceImpl;
import ai.core.server.web.AgentSessionChannelListener;
import ai.core.server.web.AgentSessionWebServiceImpl;
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

        var storagePath = property("sys.file.storagePath").orElse("./data/files");
        bind(new FileService(Path.of(storagePath)));

        bind(AgentSessionManager.class);
        bind(AgentRunner.class);
        bind(AgentScheduler.class);

        onStartup(toolRegistry::initialize);

        api().service(UserWebService.class, bind(UserWebServiceImpl.class));
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        api().service(ToolRegistryWebService.class, bind(ToolRegistryWebServiceImpl.class));
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
        api().service(AgentRunWebService.class, bind(AgentRunWebServiceImpl.class));
        api().service(AgentScheduleWebService.class, bind(AgentScheduleWebServiceImpl.class));
        api().service(FileWebService.class, bind(FileWebServiceImpl.class));

        http().route(HTTPMethod.POST, "/api/files", bind(FileUploadController.class));
        http().route(HTTPMethod.GET, "/api/files/:id/content", bind(FileDownloadController.class));

        schedule().fixedRate("agent-scheduler", bind(AgentSchedulerJob.class), Duration.ofMinutes(1));

        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.listen(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class));
    }
}
