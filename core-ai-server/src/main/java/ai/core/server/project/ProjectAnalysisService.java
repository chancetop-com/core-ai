package ai.core.server.project;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ProjectSubject;
import core.framework.async.Executor;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pipeline driver of the two project analysis stages. The high-frequency attribution run assigns
 * the members' new execution records to subjects; the low-frequency analysis run consumes the
 * attributed-but-not-yet-analyzed material per started subject, derives subject state and marks
 * the consumed attributions as analyzed (per-attribution cursor). Both runs share the single-flight
 * claim, which is heartbeaten between LLM calls so a healthy long run is never taken over while a
 * dead one is recovered after {@link #STALE_CLAIM}.
 *
 * @author stephen
 */
public class ProjectAnalysisService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAnalysisService.class);
    // one LLM call is capped at 15 minutes; the heartbeat runs between calls, so a live claim is
    // never older than that — anything past 30 minutes is a process that died mid-run
    static final Duration STALE_CLAIM = Duration.ofMinutes(30);

    @Inject
    ProjectStore projectStore;
    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    AgentDefinitionStore agentDefinitionStore;
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
    @Inject
    Executor executor;

    // single-flight claim with stale recovery
    public boolean claimAnalysis(String projectId) {
        var now = ZonedDateTime.now();
        return projectStore.updateIf(projectId,
            project -> project.analysisClaimedAt == null
                || !ProjectService.ANALYSIS_RUNNING.equals(project.analysisStatus)
                || project.analysisClaimedAt.isBefore(now.minus(STALE_CLAIM)),
            project -> {
                project.analysisStatus = ProjectService.ANALYSIS_RUNNING;
                project.analysisClaimedAt = now;
            });
    }

    // refreshes the claim between LLM calls (see STALE_CLAIM)
    void heartbeat(String projectId) {
        projectStore.update(projectId, project -> project.analysisClaimedAt = ZonedDateTime.now());
    }

    public void finishAnalysis(String projectId, String error) {
        var now = ZonedDateTime.now();
        projectStore.update(projectId, project -> {
            project.analysisStatus = error != null ? "error" : "idle";
            project.analysisError = error;
            project.updatedAt = now;
            project.analysisClaimedAt = null;
            if (error == null) {
                project.lastAnalysisAt = now;
                project.statsDirty = Boolean.TRUE;
            }
        });
    }

    // attribution stage completion stamps last_analyzed_at (the attribution job's own gate; the
    // subject-analysis job gates on last_analysis_at instead); failures leave it untouched
    public void finishAttribution(String projectId, String error) {
        var now = ZonedDateTime.now();
        projectStore.update(projectId, project -> {
            project.analysisStatus = error != null ? "error" : "idle";
            project.analysisError = error;
            project.updatedAt = now;
            project.analysisClaimedAt = null;
            if (error == null) {
                project.lastAnalyzedAt = now;
                project.statsDirty = Boolean.TRUE;   // new material → cost snapshot is stale
            }
        });
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
                    heartbeat(projectId);
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

    // manual "Analyze now" (claim already taken by the caller): runs in the background — an
    // attribution pass plus one LLM call per subject takes minutes, far past any HTTP timeout.
    // The UI polls analysis_status until it leaves "running".
    public void submitManualAnalysis(String projectId, String focusSubjectId) {
        executor.submit("project-analysis", () -> runManualAnalysis(projectId, focusSubjectId));
    }

    // a full pass — first attribute the members' new material, then analyze. The first manual
    // analysis of a subject marks it started, which opts it into scheduled runs.
    public AnalysisResult runManualAnalysis(String projectId, String focusSubjectId) {
        try {
            if (focusSubjectId != null && !focusSubjectId.isBlank()) {
                stateService.recordSubjectStatus(projectId, focusSubjectId, "started", ProjectSubjectAnalysisStage.WRITER);
            }
            int attributed = attributionStage.run(projectId);
            heartbeat(projectId);
            var result = analyzeTargets(projectId, focusSubjectId);
            finishAnalysis(projectId, null);
            projectStore.update(projectId, project -> project.lastAnalyzedAt = ZonedDateTime.now());
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
            heartbeat(projectId);
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
        for (var subject : subjectStore.listByProject(projectId)) {
            if (focusSubjectId != null && !focusSubjectId.isBlank()) {
                if (!focusSubjectId.equals(subject.id)) continue;
            } else if (!"started".equals(subject.status)) {
                continue;
            }
            if (subjectAnalysisStage.hasUnanalyzedMaterial(subject.id)) targets.add(subject);
        }
        return targets;
    }

    // restores the builtin definitions to their defaults (admin action; user edits are overwritten)
    public void resetBuiltinAgents() {
        var now = new java.util.Date();
        var docs = Map.of(
            ProjectBuiltinAgents.ATTRIBUTOR, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.ATTRIBUTOR,
                ProjectBuiltinAgents.ATTRIBUTOR, ProjectBuiltinAgents.ATTRIBUTOR_DESCRIPTION,
                ProjectBuiltinAgents.attributorPrompt(), ProjectBuiltinAgents.attributionSchema(), now),
            ProjectBuiltinAgents.SUBJECT_ANALYZER, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.SUBJECT_ANALYZER,
                ProjectBuiltinAgents.SUBJECT_ANALYZER, ProjectBuiltinAgents.SUBJECT_ANALYZER_DESCRIPTION,
                ProjectBuiltinAgents.subjectAnalyzerPrompt(), ProjectBuiltinAgents.subjectAnalysisSchema(), now),
            ProjectBuiltinAgents.PLAYBOOK_WRITER, ProjectBuiltinAgents.playbookWriterDoc(now),
            ProjectBuiltinAgents.REPORT_RENDERER, ProjectBuiltinAgents.reportRendererDoc(now));
        for (var doc : docs.values()) {
            agentDefinitionStore.upsert(toDefinition(doc));
        }
    }

    private AgentDefinition toDefinition(org.bson.Document doc) {
        var definition = new AgentDefinition();
        definition.id = doc.getString("_id");
        definition.userId = doc.getString("user_id");
        definition.name = doc.getString("name");
        definition.nameKey = doc.getString("name_key");
        definition.description = doc.getString("description");
        definition.systemPrompt = doc.getString("system_prompt");
        definition.responseSchema = doc.getString("response_schema");
        definition.maxTurns = doc.getInteger("max_turns");
        definition.timeoutSeconds = doc.getInteger("timeout_seconds");
        definition.systemDefault = doc.getBoolean("system_default");
        definition.type = DefinitionType.valueOf(doc.getString("type"));
        definition.status = AgentStatus.valueOf(doc.getString("status"));
        definition.tools = tools(doc.getList("tools", org.bson.Document.class));
        definition.publishedAt = zoned(doc.getDate("published_at"));
        definition.createdAt = zoned(doc.getDate("created_at"));
        definition.updatedAt = zoned(doc.getDate("updated_at"));
        var published = doc.get("published_config", org.bson.Document.class);
        if (published != null) {
            var config = new AgentPublishedConfig();
            config.systemPrompt = published.getString("system_prompt");
            config.responseSchema = published.getString("response_schema");
            config.maxTurns = published.getInteger("max_turns");
            config.timeoutSeconds = published.getInteger("timeout_seconds");
            config.tools = tools(published.getList("tools", org.bson.Document.class));
            definition.publishedConfig = config;
        }
        return definition;
    }

    private List<ToolRef> tools(List<org.bson.Document> docs) {
        if (docs == null) return null;
        return docs.stream()
            .map(doc -> ToolRef.of(doc.getString("id"), ToolSourceType.valueOf(doc.getString("type"))))
            .toList();
    }

    private ZonedDateTime zoned(java.util.Date date) {
        return date == null ? null : ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    public record AnalysisResult(int attributed, int analyzed, int updated) {
    }
}
