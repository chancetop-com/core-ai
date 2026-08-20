package ai.core.server.project;

import ai.core.server.domain.Project;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Recomputes the cached cost snapshots of projects that were marked dirty (members changed,
 * attribution advanced, analysis completed). Stats are read from the cache by the API, so this
 * job keeps the dashboard fast while staying at most one tick behind the updates.
 *
 * @author stephen
 */
public class ProjectStatsRefreshJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectStatsRefreshJob.class);
    static final int MAX_PROJECTS_PER_TICK = 10;

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    ProjectStatsQueryService statsQueryService;

    @Override
    public void execute(JobContext context) {
        // cost snapshots aggregate the member traces; large projects take longer than the 10s scheduler default
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));
        var query = new Query();
        query.filter = Filters.and(
            Filters.eq("status", ProjectService.STATUS_ACTIVE),
            Filters.eq("stats_dirty", Boolean.TRUE));
        query.limit = MAX_PROJECTS_PER_TICK;
        for (var project : projectCollection.find(query)) {
            LOGGER.info("project stats refresh triggered, projectId={}", project.id);
            statsQueryService.refresh(project.id);
        }
    }
}
