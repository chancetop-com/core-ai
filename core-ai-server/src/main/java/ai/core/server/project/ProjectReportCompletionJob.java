package ai.core.server.project;

import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

import java.time.Duration;

/**
 * Completes in-flight report renders: the renderer is an agent run (submitted by the manual
 * Regenerate trigger), and this job polls subjects with a pending report_run_id, assembles the
 * draft sections into the final report artifact when the run finished, or records report_error
 * when it failed. Runs every 30 seconds so a finished render is assembled almost immediately.
 *
 * @author stephen
 */
public class ProjectReportCompletionJob implements Job {
    static final int MAX_SUBJECTS_PER_TICK = 20;

    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    ProjectReportStage reportStage;

    @Override
    public void execute(JobContext context) {
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));
        for (var subject : subjectStore.listWithReportRunId(MAX_SUBJECTS_PER_TICK)) {
            reportStage.complete(subject.id);
        }
    }
}
