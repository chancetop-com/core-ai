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
 * Dependency holder for {@link InProcessCommandHandler}.
 *
 * @author xander
 */
public class InProcessCommandHandlerDependencies {
    public AgentSessionManager sessionManager;
    public ChatMessageService chatMessageService;
    public SessionOwnershipRegistry ownershipRegistry;
    public AgentDraftGenerator agentDraftGenerator;
    public AgentDefinitionService agentDefinitionService;
    public ServerA2AService serverA2AService;
    public JedisPool jedisPool;
    public SandboxService sandboxService;
    public EventPublisher eventPublisher;
    public ToolRegistryService toolRegistryService;
}
