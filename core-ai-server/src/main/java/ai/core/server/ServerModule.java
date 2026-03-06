package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.api.server.auth.AuthWebService;
import ai.core.api.server.FileWebService;
import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.UserWebService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.auth.AuthService;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.file.FileDownloadController;
import ai.core.server.file.FileService;
import ai.core.server.file.FileUploadController;
import ai.core.server.domain.migration.SchemaMigrationManager;
import ai.core.server.run.AgentRunService;
import ai.core.server.run.AgentRunner;
import ai.core.server.schedule.AgentScheduleService;
import ai.core.server.schedule.AgentScheduler;
import ai.core.server.schedule.AgentSchedulerJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.user.UserService;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
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

        bind(AgentRunner.class);
        bind(AgentScheduler.class);
        bind(AgentSessionManager.class);
        bind(AgentDefinitionService.class);
        bind(AgentRunService.class);
        bind(AgentScheduleService.class);
        bind(UserService.class);
        var authService = bind(AuthService.class);
        authService.adminEmail = property("sys.admin.email").orElse("admin@example.com");
        authService.adminPassword = property("sys.admin.password").orElse("admin");
        authService.adminName = property("sys.admin.name").orElse("Admin");
        bind(ChannelService.class);
        bind(SessionChannelService.class);

        onStartup(toolRegistry::initialize);
        onStartup(authService::initialize);

        api().service(AuthWebService.class, bind(AuthWebServiceImpl.class));
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