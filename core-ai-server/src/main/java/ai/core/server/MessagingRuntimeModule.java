package ai.core.server;

import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.asynctask.AsyncToolTaskService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.messaging.CommandBusTaskNotificationDispatcher;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.CommandRpcDependencies;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.InProcessCommandHandler;
import ai.core.server.messaging.InternalRpcController;
import ai.core.server.messaging.PgCommandCleanupJob;
import ai.core.server.messaging.PgCommandConsumer;
import ai.core.server.messaging.PgCommandQueue;
import ai.core.server.messaging.PgCommandRow;
import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.SessionCommandDependencies;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandboxhub.SandboxHubService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionRegistry;
import ai.core.server.sse.SseEndpointRegistry;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.AgentSessionWebServiceImpl;
import ai.core.server.web.sse.AgentMessageStreamChannelListener;
import ai.core.server.web.sse.InternalSseEventController;
import ai.core.server.web.sse.SessionEventHubRegistry;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
class MessagingRuntimeModule extends Module {
    @Override
    protected void initialize() {
        var ownershipRegistry = bean(SessionOwnershipRegistry.class);
        var sandboxService = bean(SandboxService.class);
        var asyncToolTaskService = bean(AsyncToolTaskService.class);
        var sessionDependencies = new SessionCommandDependencies(bean(AgentSessionManager.class), bean(ChatMessageService.class),
                ownershipRegistry, sandboxService, bean(EventPublisher.class), bean(ObjectStorageServiceResolver.class),
                bean(SessionAttachmentRefRepository.class), asyncToolTaskService, bean(ai.core.server.session.SessionNoticePrompt.class));
        var rpcDependencies = new CommandRpcDependencies(bean(AgentDraftGenerator.class), bean(AgentDefinitionService.class),
                bean(ServerA2AService.class), bean(ToolRegistryService.class), bean(SandboxHubService.class));
        var commandHandler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);

        var controller = new InternalRpcController(commandHandler, bean(PodRegistry.class),
                property("sys.internal.token").orElse(""), property("sys.internal.token.previous").orElse(""));
        http().route(HTTPMethod.POST, "/_internal/rpc", controller::handle);

        var sseController = new InternalSseEventController(bean(PodRegistry.class), ownershipRegistry,
                bean(SessionEventHubRegistry.class), property("sys.internal.token").orElse(""),
                property("sys.internal.token.previous").orElse(""));
        http().route(HTTPMethod.POST, "/_internal/sessions/events", sseController::handle);

        // unowned sessions run on the instance that received the request (its SSE channel is here; nothing else on the
        // same database can race for the turn); false restores distribution through the shared unowned queue
        var claimLocally = "true".equalsIgnoreCase(property("sys.session.claimLocally").orElse("true"));
        var pgQueue = bind(PgCommandQueue.class);
        var commandPublisher = bind(new CommandPublisher(ownershipRegistry, sandboxService, commandHandler, claimLocally, pgQueue));
        db().view(PgCommandRow.class);
        var pgConsumer = new PgCommandConsumer(pgQueue, commandHandler, ownershipRegistry);
        onStartup(pgConsumer::start);
        onShutdown(pgConsumer::stop);
        var cleanupJob = bind(new PgCommandCleanupJob(pgQueue));
        schedule().fixedRate("pg-command-cleanup", cleanupJob, Duration.ofSeconds(30));

        // finished long-running tool calls ride the command bus back into their session, so the
        // notification reaches the owning replica and rebuilds an evicted session instead of being
        // dropped when the session is not live in this JVM
        asyncToolTaskService.setNotificationDispatcher(
                new CommandBusTaskNotificationDispatcher(commandPublisher, bean(SessionRegistry.class)));
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        registerSseEndpoints();
    }

    private void registerSseEndpoints() {
        var registry = bean(SseEndpointRegistry.class);
        registry.register(HTTPMethod.POST, "/api/sessions/messages/stream", SseBaseEvent.class, bind(AgentMessageStreamChannelListener.class), false);
    }
}
