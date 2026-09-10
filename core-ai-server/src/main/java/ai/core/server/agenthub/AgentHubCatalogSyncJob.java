package ai.core.server.agenthub;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes the Agent Hub catalog every 30s. Live invalidation happens on every
 * {@code AgentDefinitionService} write path via {@link AgentCatalogService#invalidate()}.
 *
 * @author stephen
 */
public class AgentHubCatalogSyncJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentHubCatalogSyncJob.class);

    @Inject
    AgentCatalogService catalog;

    @Override
    public void execute(JobContext context) {
        try {
            catalog.refresh();
        } catch (Exception e) {
            LOGGER.error("failed to refresh agent hub catalog", e);
        }
    }
}
