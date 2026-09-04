package ai.core.server.mcphub;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes the MCP Hub tool catalog every 30s, mirroring the tool registry sync cadence.
 * Live invalidation happens on server enable/disable/config/connect via
 * {@link McpToolCatalogService#invalidate(String)}.
 *
 * @author stephen
 */
public class McpHubCatalogSyncJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpHubCatalogSyncJob.class);

    @Inject
    McpToolCatalogService catalog;

    @Override
    public void execute(JobContext context) {
        try {
            catalog.refresh();
        } catch (Exception e) {
            LOGGER.error("failed to refresh mcp hub catalog", e);
        }
    }
}
