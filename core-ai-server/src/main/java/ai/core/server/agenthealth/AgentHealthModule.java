package ai.core.server.agenthealth;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.skill.SkillService;
import ai.core.server.tool.ToolRegistryService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Bound after NotificationModule and ApiToolHubModule, then injects the health hooks into services
 * that were bound earlier. This keeps the existing module order and avoids a constructor cycle.
 *
 * @author core-ai
 */
public class AgentHealthModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            db().view(PgAgentReferenceIssueStore.IdRow.class);
            bind(AgentReferenceIssueStore.class, bind(PgAgentReferenceIssueStore.class));
        } else {
            bind(AgentReferenceIssueStore.class, bind(NoopAgentReferenceIssueStore.class));
        }

        var healthService = bind(AgentHealthService.class);
        var tracker = bind(AgentReferenceTracker.class);
        var agentDefinitionService = bean(AgentDefinitionService.class);
        agentDefinitionService.setHealthService(healthService);
        agentDefinitionService.setReferenceTracker(tracker);
        bean(SkillService.class).setHealthListener(tracker::onSkillDeleted);
        bean(ToolRegistryService.class).setApiCatalogReloadListener(tracker::onApiCatalogReloaded);

        schedule().fixedRate("agent-health-reconcile", bind(AgentHealthReconcileJob.class), Duration.ofMinutes(5));
    }
}
