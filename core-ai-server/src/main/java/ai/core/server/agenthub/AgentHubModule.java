package ai.core.server.agenthub;

import ai.core.api.server.AgentHubWebService;
import ai.core.server.agent.AgentDefinitionService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Agent Hub surface: scoring search, bare-name lookup and task-based execution over the shared
 * agent registry, without touching the management CRUD surface ({@code /api/agents/*}).
 * <p>
 * Loads after {@code A2AModule}: {@link AgentHubService} runs AGENT tasks through
 * {@code ServerA2AService} and LLM calls through {@code AgentRunService}. The catalog is
 * invalidated from {@link AgentDefinitionService} write paths and refreshed every 30s.
 *
 * @author stephen
 */
public class AgentHubModule extends Module {
    @Override
    protected void initialize() {
        var catalog = bind(AgentCatalogService.class);
        var definitionService = bean(AgentDefinitionService.class);
        onStartup(() -> definitionService.setCatalogInvalidator(catalog::invalidate));

        bind(AgentHubService.class);
        api().service(AgentHubWebService.class, bind(AgentHubWebServiceImpl.class));
        schedule().fixedRate("agent-hub-catalog-sync", bind(AgentHubCatalogSyncJob.class), Duration.ofSeconds(30));
    }
}
