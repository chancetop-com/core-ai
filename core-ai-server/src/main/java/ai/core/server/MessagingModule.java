package ai.core.server;

import ai.core.api.server.AgentSessionWebService;
import ai.core.server.a2a.A2AEventRelay;
import ai.core.server.a2a.A2ATaskRegistry;
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
class MessagingModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessagingModule.class);

    @Override
    protected void initialize() {
        var jedisPool = bean(JedisPool.class);
        var ownershipRegistry = bean(SessionOwnershipRegistry.class);
        var commandPublisher = bean(CommandPublisher.class);
        var a2aTaskRegistry = bean(A2ATaskRegistry.class);
        var a2aEventRelay = bean(A2AEventRelay.class);
        var rpcClient = bean(RpcClient.class);
        bean(AgentSessionManager.class).setEventPublisher(bean(EventPublisher.class));
        bean(AgentSessionManager.class).setOwnershipRegistry(ownershipRegistry);
        bind(PodLocalExecutor.class);
        api().service(AgentSessionWebService.class, bind(AgentSessionWebServiceImpl.class));
        registerSseEndpoints();
        bean(ServerA2AService.class).setTaskRouting(a2aTaskRegistry, ownershipRegistry, rpcClient, a2aEventRelay);
        var rpcResponseSubscriber = new RpcResponseSubscriber(jedisPool, rpcClient);
        onStartup(rpcResponseSubscriber::start);
        onShutdown(rpcResponseSubscriber::stop);
        var eventSubscriber = new EventSubscriber(jedisPool, bean(SessionChannelService.class));
        onStartup(eventSubscriber::start);
        onShutdown(eventSubscriber::stop);
        var handlerDependencies = new InProcessCommandHandlerDependencies();
        handlerDependencies.sessionManager = bean(AgentSessionManager.class);
        handlerDependencies.chatMessageService = bean(ChatMessageService.class);
        handlerDependencies.ownershipRegistry = ownershipRegistry;
        handlerDependencies.agentDraftGenerator = bean(AgentDraftGenerator.class);
        handlerDependencies.agentDefinitionService = bean(AgentDefinitionService.class);
        handlerDependencies.serverA2AService = bean(ServerA2AService.class);
        handlerDependencies.jedisPool = jedisPool;
        handlerDependencies.sandboxService = bean(SandboxService.class);
        handlerDependencies.eventPublisher = bean(EventPublisher.class);
        handlerDependencies.toolRegistryService = bean(ToolRegistryService.class);
        var commandHandler = new InProcessCommandHandler(handlerDependencies);
        commandPublisher.setCommandHandler(commandHandler);
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
