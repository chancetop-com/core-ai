package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
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
import java.time.ZonedDateTime;

/**
 * High-frequency attribution driver: for every active project with at least one started subject,
 * collects the members' new unattributed execution records since the attribution cursor and runs
 * the attribution writer (single-flight claim; the cursor advances only on success). Runs more
 * often than the subject-analysis job because attribution is cheap and just tags material.
 *
 * @author stephen
 */
public class ProjectAttributionJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAttributionJob.class);
    static final Duration ATTRIBUTION_INTERVAL = Duration.ofMinutes(10);
    static final int MAX_PROJECTS_PER_TICK = 10;

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    ProjectAnalysisService analysisService;

    @Override
    public void execute(JobContext context) {
        // LLM-driven attribution takes longer than the 10s scheduler default; raise the SLOW_PROCESS threshold
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));
        var cutoff = ZonedDateTime.now().minus(ATTRIBUTION_INTERVAL);
        var query = new Query();
        query.filter = Filters.and(
            Filters.eq("status", ProjectService.STATUS_ACTIVE),
            Filters.ne("analysis_status", ProjectService.ANALYSIS_RUNNING),
            Filters.or(Filters.exists("last_analyzed_at", false), Filters.lt("last_analyzed_at", cutoff)));
        query.limit = MAX_PROJECTS_PER_TICK;
        for (var project : projectCollection.find(query)) {
            if (!hasStartedSubject(project.id)) continue;
            if (!analysisService.claimAnalysis(project.id)) continue;
            LOGGER.info("project attribution triggered, projectId={}", project.id);
            analysisService.runAttribution(project.id);
        }
    }

    private boolean hasStartedSubject(String projectId) {
        return subjectCollection.count(Filters.and(
            Filters.eq("project_id", projectId),
            Filters.eq("status", "started"))) > 0;
    }
}
