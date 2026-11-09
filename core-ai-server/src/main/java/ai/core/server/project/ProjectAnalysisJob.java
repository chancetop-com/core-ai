package ai.core.server.project;

import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Low-frequency subject-analysis driver: for every active project whose last subject analysis is
 * older than the interval and that has a started subject with attributed-but-not-yet-analyzed
 * material, runs the subject-analysis stage (single-flight claim). The gate is
 * {@code last_analysis_at} (set by THIS stage only — the attribution stage's cursor advances every
 * ten minutes and must not push the analysis out); the per-attribution consumption marker is the
 * material cursor, so nothing is analyzed twice and nothing runs when there is nothing new.
 *
 * @author stephen
 */
public class ProjectAnalysisJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAnalysisJob.class);
    static final Duration ANALYSIS_INTERVAL = Duration.ofHours(1);
    static final int MAX_PROJECTS_PER_TICK = 5;

    @Inject
    ProjectStore projectStore;
    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    ProjectAttributionRepository attributionRepository;
    @Inject
    ProjectAnalysisService analysisService;

    @Override
    public void execute(JobContext context) {
        // LLM-driven analysis takes longer than the 10s scheduler default; raise the SLOW_PROCESS threshold
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));
        var cutoff = ZonedDateTime.now().minus(ANALYSIS_INTERVAL);
        for (var project : projectStore.listForAnalysis(cutoff, MAX_PROJECTS_PER_TICK)) {
            if (!hasUnanalyzedStartedSubject(project.id)) continue;
            if (!analysisService.claimAnalysis(project.id)) continue;
            LOGGER.info("project subject analysis triggered, projectId={}", project.id);
            analysisService.runAnalysis(project.id, null);
        }
    }

    private boolean hasUnanalyzedStartedSubject(String projectId) {
        for (var subject : subjectStore.listStarted(projectId)) {
            if (attributionRepository.countUnanalyzed(subject.id) > 0) return true;
        }
        return false;
    }
}
