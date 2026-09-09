package ai.core.server;

import ai.core.api.server.AgentSessionWebService;
import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.asynctask.AsyncToolTaskService;
import ai.core.server.messaging.CommandBusTaskNotificationDispatcher;
import ai.core.server.messaging.CommandConsumer;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.EventSubscriber;
import ai.core.server.messaging.InProcessCommandHandler;
import ai.core.server.messaging.CommandRpcDependencies;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.RpcResponseSubscriber;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.SessionCommandDependencies;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionRegistry;
import ai.core.server.sse.SseEndpointRegistry;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.AgentSessionWebServiceImpl;
import ai.core.server.web.PodLocalExecutor;
import ai.core.server.web.sse.AgentMessageStreamChannelListener;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.api.server.session.sse.SseBaseEvent;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

import java.util.ArrayList;

/**
 * @author stephen
 */
class MessagingRuntimeModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessagingRuntimeModule.class);

    @Override
    protected void initialize() {
        var jedisPool = bean(JedisPool.class);
        var ownershipRegistry = bean(SessionOwnershipRegistry.class);
        var rpcClient = bean(RpcClient.class);
        var rpcResponseSubscriber = new RpcResponseSubscriber(jedisPool, rpcClient);
        onStartup(rpcResponseSubscriber::start);
        onShutdown(rpcResponseSubscriber::stop);
        var eventSubscriber = new EventSubscriber(jedisPool, bean(SessionChannelService.class));
        onStartup(eventSubscriber::start);
        onShutdown(eventSubscriber::stop);
        var sandboxService = bean(SandboxService.class);
        var asyncToolTaskService = bean(AsyncToolTaskService.class);
        var sessionDependencies = new SessionCommandDependencies(bean(AgentSessionManager.class), bean(ChatMessageService.class),
                ownershipRegistry, sandboxService, bean(EventPublisher.class), bean(ObjectStorageServiceResolver.class),
                bean(SessionAttachmentRefRepository.class), asyncToolTaskService);
        var rpcDependencies = new CommandRpcDependencies(bean(AgentDraftGenerator.class), bean(AgentDefinitionService.class),
                bean(ServerA2AService.class), jedisPool, bean(ToolRegistryService.class));
        var commandHandler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);
        // unowned sessions run on the instance that received the request (its SSE channel is here; nothing else on the
        // same Redis can race for the turn); false restores distribution through the shared unowned stream
        var claimLocally = "true".equalsIgnoreCase(property("sys.session.claimLocally").orElse("true"));
        var commandPublisher = bind(new CommandPublisher(jedisPool, ownershipRegistry, sandboxService, commandHandler, claimLocally));
        // finished long-running tool calls ride the command bus back into their session, so the
        // notification reaches the owning replica and rebuilds an evicted session instead of being
        // dropped when the session is not live in this JVM
        asyncToolTaskService.setNotificationDispatcher(
                new CommandBusTaskNotificationDispatcher(commandPublisher, bean(SessionRegistry.class)));
        bind(PodLocalExecutor.class);
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        registerSseEndpoints();
        setupCommandConsumer(jedisPool, ownershipRegistry, commandHandler);

    }

    private void registerSseEndpoints() {
        var registry = bean(SseEndpointRegistry.class);
        registry.register(HTTPMethod.POST, "/api/sessions/messages/stream", SseBaseEvent.class, bind(AgentMessageStreamChannelListener.class), false);
    }

    private void setupCommandConsumer(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry, InProcessCommandHandler commandHandler) {
        var commandConsumer = new CommandConsumer(jedisPool, commandHandler, ownershipRegistry);
        onStartup(commandConsumer::start);
        onShutdown(() -> {
            commandConsumer.stop();
            var pending = new ArrayList<SessionCommand>();
            commandConsumer.drainPodStream(pending);
            if (!pending.isEmpty()) {
                LOGGER.info("republishing {} pending commands to unowned stream", pending.size());
                try (var jedis = jedisPool.getResource()) {
                    for (var cmd : pending) {
                        jedis.xadd(SessionCommand.UNOWNED_STREAM, StreamEntryID.NEW_ENTRY, cmd.toStreamMap());
                    }
                }
            }
        });
    }
}
