package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline driver of the two project analysis stages. The high-frequency attribution run assigns
 * the members' new execution records to subjects; the low-frequency analysis run consumes the
 * attributed-but-not-yet-analyzed material per started subject, derives subject state and marks
 * the consumed attributions as analyzed (per-attribution cursor). Both runs share the single-flight
 * claim; the attribution cursor advances only when the attribution stage succeeds.
 *
 * @author stephen
 */
public class ProjectAnalysisService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAnalysisService.class);

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    ProjectAttributionStage attributionStage;
    @Inject
    ProjectSubjectAnalysisStage subjectAnalysisStage;
    @Inject
    ProjectStatsQueryService statsQueryService;
    @Inject
    ProjectStateService stateService;
    @Inject
    ProjectReportStage reportStage;

    // single-flight claim with stale recovery: a claim older than 10 minutes is treated as dead
    // (e.g. the process died mid-run) and can be taken over
    public boolean claimAnalysis(String projectId) {
        long updated = projectCollection.update(Filters.and(
                Filters.eq("_id", projectId),
                Filters.or(
                    Filters.ne("analysis_status", ProjectService.ANALYSIS_RUNNING),
                    Filters.lt("analysis_claimed_at", ZonedDateTime.now().minusMinutes(10)))),
            Updates.combine(
                Updates.set("analysis_status", ProjectService.ANALYSIS_RUNNING),
                Updates.set("analysis_claimed_at", ZonedDateTime.now())));
        return updated > 0;
    }

    public void finishAnalysis(String projectId, String error) {
        var now = ZonedDateTime.now();
        var list = new ArrayList<org.bson.conversions.Bson>();
        list.add(Updates.set("analysis_status", error != null ? "error" : "idle"));
        list.add(Updates.set("analysis_error", error));
        list.add(Updates.set("updated_at", now));
        list.add(Updates.unset("analysis_claimed_at"));
        if (error == null) {
            list.add(Updates.set("last_analysis_at", now));
            list.add(Updates.set("stats_dirty", Boolean.TRUE));
        }
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(list));
    }

    // attribution stage completion also advances the attribution cursor (material newer than this
    // will be considered by the next attribution run); failures leave the cursor untouched
    public void finishAttribution(String projectId, String error) {
        var list = new ArrayList<org.bson.conversions.Bson>();
        list.add(Updates.set("analysis_status", error != null ? "error" : "idle"));
        list.add(Updates.set("analysis_error", error));
        list.add(Updates.set("updated_at", ZonedDateTime.now()));
        list.add(Updates.unset("analysis_claimed_at"));
        if (error == null) {
            list.add(Updates.set("last_analyzed_at", ZonedDateTime.now()));
            list.add(Updates.set("stats_dirty", Boolean.TRUE));   // new material → cost snapshot is stale
        }
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(list));
    }

    public void runAttribution(String projectId) {
        // retry once right away: the second attempt usually hits a warm model cache and finishes
        // well before the upstream idle timeout; only failures of both attempts wait for the
        // next scheduled tick
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                attributionStage.run(projectId);
                finishAttribution(projectId, null);
                return;
            } catch (RuntimeException e) {
                if (attempt == 1) {
                    LOGGER.error("project attribution failed, projectId={}, retrying once", projectId, e);
                    continue;
                }
                LOGGER.error("project attribution retry failed, projectId={}", projectId, e);
                finishAttribution(projectId, e.getMessage());
            }
        }
    }

    public void runAnalysis(String projectId, String focusSubjectId) {
        try {
            var result = analyzeTargets(projectId, focusSubjectId);
            LOGGER.info("project subject analysis completed, projectId={}, analyzed={}, updated={}",
                projectId, result.analyzed(), result.updated());
            finishAnalysis(projectId, null);
        } catch (RuntimeException e) {
            LOGGER.error("project analysis failed, projectId={}", projectId, e);
            finishAnalysis(projectId, e.getMessage());
        }
    }

    // manual "Analyze now": a full pass — first attribute the members' new material, then analyze.
    // The first manual analysis of a subject marks it started, which opts it into scheduled runs.
    public AnalysisResult runManualAnalysis(String projectId, String focusSubjectId) {
        try {
            if (focusSubjectId != null && !focusSubjectId.isBlank()) {
                stateService.recordSubjectStatus(projectId, focusSubjectId, "started", ProjectSubjectAnalysisStage.WRITER);
            }
            int attributed = attributionStage.run(projectId);
            finishAttribution(projectId, null);
            var result = analyzeTargets(projectId, focusSubjectId);
            finishAnalysis(projectId, null);
            // manual runs refresh the cost snapshot right away (scheduled runs rely on the stats job)
            statsQueryService.refresh(projectId);
            return new AnalysisResult(attributed, result.analyzed(), result.updated());
        } catch (RuntimeException e) {
            LOGGER.error("manual project analysis failed, projectId={}", projectId, e);
            finishAnalysis(projectId, e.getMessage());
            throw e;
        }
    }

    // manual regenerate: submits the renderer AGENT run (async — ProjectReportCompletionJob
    // assembles the sections when it finishes). The render is idempotent and takes no claim, so an
    // interrupted run never leaves a stuck state behind. User-actionable failures (already
    // rendering / no events / missing definition) are rethrown for the web layer.
    public void regenerateReport(String projectId, String subjectId) {
        reportStage.submit(projectId, subjectId);
    }

    private AnalysisResult analyzeTargets(String projectId, String focusSubjectId) {
        int analyzed = 0;
        int updated = 0;
        var targets = analysisTargets(projectId, focusSubjectId);
        for (var subject : targets) {
            var result = subjectAnalysisStage.run(projectId, subject.id);
            analyzed += result.consumed();
            updated += result.updated();
        }
        return new AnalysisResult(0, analyzed, updated);
    }

    // started subjects only (a manual focus run overrides the status gate); only subjects with
    // attributed-but-not-yet-analyzed material are worth analyzing
    private List<ProjectSubject> analysisTargets(String projectId, String focusSubjectId) {
        var targets = new ArrayList<ProjectSubject>();
        for (var subject : subjectCollection.find(Filters.eq("project_id", projectId))) {
            if (focusSubjectId != null && !focusSubjectId.isBlank()) {
                if (!focusSubjectId.equals(subject.id)) continue;
            } else if (!"started".equals(subject.status)) {
                continue;
            }
            if (subjectAnalysisStage.hasUnanalyzedMaterial(subject.id)) targets.add(subject);
        }
        return targets;
    }

    // restores the four builtin definitions to their defaults (admin action; user edits are overwritten)
    public void resetBuiltinAgents() {
        var now = new java.util.Date();
        var docs = java.util.Map.of(
            ProjectBuiltinAgents.PROJECT_AGENT, ProjectBuiltinAgents.mainAgentDoc(now),
            ProjectBuiltinAgents.ATTRIBUTOR, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.ATTRIBUTOR,
                ProjectBuiltinAgents.ATTRIBUTOR,
                "Attributes the targets listed in the query to project subjects. Prepare the query as the SUBJECTS list plus a digest of the unattributed targets; the result is applied to the attribution table automatically.",
                ProjectBuiltinAgents.ATTRIBUTOR_PROMPT, ProjectBuiltinAgents.attributionSchema(), now),
            ProjectBuiltinAgents.SUBJECT_ANALYZER, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.SUBJECT_ANALYZER,
                ProjectBuiltinAgents.SUBJECT_ANALYZER,
                "Derives ONE subject's status/KPIs/action items/notes from the query (playbook + subject context + current state + material digest) and applies them automatically; pass the subject_id.",
                ProjectBuiltinAgents.subjectAnalyzerPrompt(), ProjectBuiltinAgents.subjectAnalysisSchema(), now),
            ProjectBuiltinAgents.REPORT_RENDERER, ProjectBuiltinAgents.reportRendererDoc(now));
        for (var entry : docs.entrySet()) {
            var doc = entry.getValue();
            var set = new org.bson.Document();
            doc.forEach((key, value) -> {
                if (!"_id".equals(key)) set.append(key, value);
            });
            agentCollection.update(Filters.eq("_id", doc.getString("_id")), Updates.combine(
                new org.bson.Document("$set", set)));
        }
    }

    public record AnalysisResult(int attributed, int analyzed, int updated) {
    }
}
