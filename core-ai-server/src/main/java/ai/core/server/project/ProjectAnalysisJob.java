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
 * Low-frequency subject-analysis driver: for every active project that has a started subject with
 * attributed-but-not-yet-analyzed material, runs the subject-analysis stage (single-flight claim).
 * The per-attribution consumption marker is the cursor — no analysis runs when there is nothing
 * new, and no material is ever analyzed twice. The "Analyze now" button triggers the same stage
 * on demand.
 *
 * @author stephen
 */
public class ProjectAnalysisJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAnalysisJob.class);
    static final Duration ANALYSIS_INTERVAL = Duration.ofHours(1);
    static final int MAX_PROJECTS_PER_TICK = 5;

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
        // LLM-driven analysis takes longer than the 10s scheduler default; raise the SLOW_PROCESS threshold
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));
        var cutoff = ZonedDateTime.now().minus(ANALYSIS_INTERVAL);
        var query = new Query();
        query.filter = Filters.and(
            Filters.eq("status", ProjectService.STATUS_ACTIVE),
            Filters.ne("analysis_status", ProjectService.ANALYSIS_RUNNING),
            Filters.or(Filters.exists("last_analyzed_at", false), Filters.lt("last_analyzed_at", cutoff)));
        query.limit = MAX_PROJECTS_PER_TICK;
        for (var project : projectCollection.find(query)) {
            if (!hasUnanalyzedStartedSubject(project.id)) continue;
            if (!analysisService.claimAnalysis(project.id)) continue;
            LOGGER.info("project subject analysis triggered, projectId={}", project.id);
            analysisService.runAnalysis(project.id, null);
        }
    }

    private boolean hasUnanalyzedStartedSubject(String projectId) {
        var started = new Query();
        started.filter = Filters.and(
            Filters.eq("project_id", projectId),
            Filters.eq("status", "started"));
        for (var subject : subjectCollection.find(started)) {
            if (attributionCollection.count(Filters.and(
                    Filters.eq("subject_id", subject.id),
                    Filters.or(Filters.exists("analyzed_at", false), Filters.eq("analyzed_at", null)))) > 0) {
                return true;
            }
        }
        return false;
    }
}
