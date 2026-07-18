package ai.core.server.messaging;

import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.tool.ToolRegistryService;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
public record CommandRpcDependencies(AgentDraftGenerator agentDraftGenerator, AgentDefinitionService agentDefinitionService,
                                     ServerA2AService serverA2AService, JedisPool jedisPool,
                                     ToolRegistryService toolRegistryService) {
}
