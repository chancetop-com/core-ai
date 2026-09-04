package ai.core.server.skillhub;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes the Skill Hub catalog every 30s. Live invalidation happens on every
 * SkillService write path via {@link SkillCatalogService#invalidate()}.
 *
 * @author stephen
 */
public class SkillHubCatalogSyncJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillHubCatalogSyncJob.class);

    @Inject
    SkillCatalogService catalog;

    @Override
    public void execute(JobContext context) {
        try {
            catalog.refresh();
        } catch (Exception e) {
            LOGGER.error("failed to refresh skill hub catalog", e);
        }
    }
}
