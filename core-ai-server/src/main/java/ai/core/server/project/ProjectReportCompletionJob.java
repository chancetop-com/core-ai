package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
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
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    ProjectReportStage reportStage;

    @Override
    public void execute(JobContext context) {
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));
        var query = new Query();
        // partial index on report_run_id is {$type: "string"}: the query must state the type
        // explicitly ($ne null alone cannot prove it, which trips notablescan)
        query.filter = Filters.and(
            Filters.exists("report_run_id", true),
            Filters.type("report_run_id", "string"));
        query.limit = MAX_SUBJECTS_PER_TICK;
        for (var subject : subjectCollection.find(query)) {
            reportStage.complete(subject.id);
        }
    }
}
