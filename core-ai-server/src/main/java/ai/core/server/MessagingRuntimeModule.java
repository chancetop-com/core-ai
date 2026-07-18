package ai.core.server;

import ai.core.api.server.AgentSessionWebService;
import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.messaging.CommandConsumer;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.EventSubscriber;
import ai.core.server.messaging.InProcessCommandHandler;
import ai.core.server.messaging.InProcessCommandHandlerDependencies;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.RpcResponseSubscriber;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.AgentSessionWebServiceImpl;
import ai.core.server.web.PodLocalExecutor;
import ai.core.server.web.sse.AgentMessageStreamChannelListener;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.sse.PatchedServerSentEventConfig;
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
        var commandHandler = new InProcessCommandHandler(new InProcessCommandHandlerDependencies()
                .sessionManager(bean(AgentSessionManager.class))
                .chatMessageService(bean(ChatMessageService.class))
                .ownershipRegistry(ownershipRegistry)
                .agentDraftGenerator(bean(AgentDraftGenerator.class))
                .agentDefinitionService(bean(AgentDefinitionService.class))
                .serverA2AService(bean(ServerA2AService.class))
                .jedisPool(jedisPool)
                .sandboxService(sandboxService)
                .eventPublisher(bean(EventPublisher.class))
                .toolRegistryService(bean(ToolRegistryService.class)));
        bind(new CommandPublisher(jedisPool, ownershipRegistry, sandboxService, commandHandler));
        bind(PodLocalExecutor.class);
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        registerSseEndpoints();
        setupCommandConsumer(jedisPool, ownershipRegistry, commandHandler);

    }

    private void registerSseEndpoints() {
        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.listen(HTTPMethod.POST, "/api/sessions/messages/stream", SseBaseEvent.class, bind(AgentMessageStreamChannelListener.class));
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
