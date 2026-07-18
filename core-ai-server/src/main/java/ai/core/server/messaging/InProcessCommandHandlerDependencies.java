package ai.core.server.messaging;

import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.tool.ToolRegistryService;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
public class InProcessCommandHandlerDependencies {
    AgentSessionManager sessionManager;
    ChatMessageService chatMessageService;
    SessionOwnershipRegistry ownershipRegistry;
    AgentDraftGenerator agentDraftGenerator;
    AgentDefinitionService agentDefinitionService;
    ServerA2AService serverA2AService;
    JedisPool jedisPool;
    SandboxService sandboxService;
    EventPublisher eventPublisher;
    ToolRegistryService toolRegistryService;

    public InProcessCommandHandlerDependencies sessionManager(AgentSessionManager value) {
        sessionManager = value;
        return this;
    }

    public InProcessCommandHandlerDependencies chatMessageService(ChatMessageService value) {
        chatMessageService = value;
        return this;
    }

    public InProcessCommandHandlerDependencies ownershipRegistry(SessionOwnershipRegistry value) {
        ownershipRegistry = value;
        return this;
    }

    public InProcessCommandHandlerDependencies agentDraftGenerator(AgentDraftGenerator value) {
        agentDraftGenerator = value;
        return this;
    }

    public InProcessCommandHandlerDependencies agentDefinitionService(AgentDefinitionService value) {
        agentDefinitionService = value;
        return this;
    }

    public InProcessCommandHandlerDependencies serverA2AService(ServerA2AService value) {
        serverA2AService = value;
        return this;
    }

    public InProcessCommandHandlerDependencies jedisPool(JedisPool value) {
        jedisPool = value;
        return this;
    }

    public InProcessCommandHandlerDependencies sandboxService(SandboxService value) {
        sandboxService = value;
        return this;
    }

    public InProcessCommandHandlerDependencies eventPublisher(EventPublisher value) {
        eventPublisher = value;
        return this;
    }

    public InProcessCommandHandlerDependencies toolRegistryService(ToolRegistryService value) {
        toolRegistryService = value;
        return this;
    }
}
