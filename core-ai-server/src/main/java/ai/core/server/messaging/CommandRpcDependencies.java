package ai.core.server.messaging;

import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.sandboxhub.SandboxHubService;
import ai.core.server.tool.ToolRegistryService;

/**
 * @author stephen
 */
public record CommandRpcDependencies(AgentDraftGenerator agentDraftGenerator, AgentDefinitionService agentDefinitionService,
                                     ServerA2AService serverA2AService,
                                     ToolRegistryService toolRegistryService, SandboxHubService sandboxHubService) {
}
