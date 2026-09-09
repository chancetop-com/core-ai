package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.run.LLMCallExecutor;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Subject-analysis stage of the project analysis pipeline (low-frequency): takes the subject's
 * attributed-but-not-yet-analyzed material (attribution rows with a null consumption marker),
 * runs the tunable {@code project-subject-analyzer} LLM_CALL definition on it in CHRONOLOGICAL
 * order and applies the derived status/KPIs/action items/notes. Only the rows whose material was
 * actually handed to the LLM are marked analyzed (per-attribution cursor): rows beyond the per-run
 * caps wait for the next run, nothing is silently dropped and nothing is analyzed twice.
 *
 * @author stephen
 */
public class ProjectSubjectAnalysisStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSubjectAnalysisStage.class);
    static final int MAX_ROWS = 100;
    static final int MAX_KPI_STATE = 50;
    static final String WRITER = "project-agent";

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<ProjectSubjectEvent> eventCollection;
    @Inject
    ProjectStateService stateService;
    @Inject
    ProjectAnalysisMaterialLoader materialLoader;
    @Inject
    LLMCallExecutor llmCallExecutor;

    public boolean hasUnanalyzedMaterial(String subjectId) {
        return attributionCollection.count(unanalyzedFilter(subjectId)) > 0;
    }

    public SubjectAnalysisResult run(String projectId, String subjectId) {
        var subject = subjectCollection.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) return new SubjectAnalysisResult(0, 0);
        var unanalyzed = unanalyzedRows(subjectId);
        if (unanalyzed.isEmpty()) return new SubjectAnalysisResult(0, 0);
        var material = materialLoader.load(unanalyzed);
        if (material.consumed().isEmpty()) return new SubjectAnalysisResult(0, 0);
        if (material.digest().isBlank()) {
            markAnalyzed(material.consumed(), subject);   // targets gone / unreadable: consumed anyway (cursor progress)
            return new SubjectAnalysisResult(material.consumed().size(), 0);
        }
        var definition = agentCollection.get("builtin-" + ProjectBuiltinAgents.SUBJECT_ANALYZER).orElse(null);
        if (definition == null) {
            throw new IllegalStateException("subject analyzer definition is missing; reset builtin agents to restore it");
        }
        var input = buildInput(projectId, subject, material.digest());
        // explicit long timeout: reasoning models stay silent for minutes while thinking and the
        // default call timeout has been observed to cut such streams short
        var output = llmCallExecutor.execute(definition, input, null, 900).output();
        var count = applyUpdates(projectId, subjectId, output);
        markAnalyzed(material.consumed(), subject);
        LOGGER.info("subject analysis applied, subjectId={}, consumed={}, deferred={}, updated={}",
            subjectId, material.consumed().size(), unanalyzed.size() - material.consumed().size(), count);
        return new SubjectAnalysisResult(material.consumed().size(), count);
    }

    private org.bson.conversions.Bson unanalyzedFilter(String subjectId) {
        return Filters.and(
            Filters.eq("subject_id", subjectId),
            Filters.or(Filters.exists("analyzed_at", false), Filters.eq("analyzed_at", null)));
    }

    private List<ProjectSubjectAttribution> unanalyzedRows(String subjectId) {
        var query = new Query();
        query.filter = unanalyzedFilter(subjectId);
        query.limit = MAX_ROWS;
        return attributionCollection.find(query);
    }

    // per-row consumption marker + the material time consumed (a grown session is re-opened by the
    // attribution stage and only its newer messages are fed next time)
    private void markAnalyzed(List<ProjectAnalysisMaterialLoader.Consumed> consumed, ProjectSubject subject) {
        var now = ZonedDateTime.now();
        for (var item : consumed) {
            var updates = Updates.combine(Updates.set("analyzed_at", now),
                item.materialAt() != null ? Updates.set("analyzed_through", item.materialAt()) : Updates.unset("analyzed_through"));
            attributionCollection.update(Filters.eq("_id", item.row().id), updates);
        }
        subjectCollection.update(Filters.eq("_id", subject.id), Updates.combine(
            Updates.set("analyzed_at", now),
            Updates.set("updated_at", now)));
    }

    private String buildInput(String projectId, ProjectSubject subject, String digest) {
        var project = projectCollection.get(projectId).orElse(null);
        var playbook = project != null && project.playbook != null && !project.playbook.isBlank()
            ? "PLAYBOOK:\n" + limit(project.playbook, 20000) + "\n\n"
            : "";
        var description = subject.description != null && !subject.description.isBlank()
            ? " — " + subject.description : "";
        return playbook
            + "SUBJECT: " + subject.name + description
            + "\n\nCURRENT STATE:\n" + currentState(subject)
            + "\nATTRIBUTED MATERIAL (chronological):\n" + digest
            + "\n\nReturn the analysis JSON now.";
    }

    // everything the analyzer should already know about the subject: its profile (stable facts,
    // extracted once), the current phase/summary, the latest value of every KPI key and the open
    // action items. The LLM must NOT re-derive these from scratch — it updates them incrementally.
    private String currentState(ProjectSubject subject) {
        var digest = new StringBuilder(512);
        if (subject.profile != null && !subject.profile.isBlank()) {
            digest.append("- profile: ").append(limit(subject.profile, 2000)).append('\n');
        }
        if (subject.phase != null || subject.summary != null) {
            digest.append("- phase: ").append(limit(subject.phase, 200)).append("\n- summary: ")
                .append(limit(subject.summary, 500)).append('\n');
        }
        for (var entry : latestKpis(subject.id).entrySet()) {
            digest.append("- kpi ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
        if (subject.actionItems != null) {
            for (var item : subject.actionItems) {
                if ("done".equals(item.status)) continue;
                digest.append("- action item ").append(item.id).append(": ")
                    .append(limit(item.title, 200)).append(" (").append(item.status).append(")\n");
            }
        }
        return digest.length() == 0 ? "(none)\n" : digest.toString();
    }

    // latest value per KPI key from the event series (newest first, first hit per key wins)
    private Map<String, String> latestKpis(String subjectId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("subject_id", subjectId), Filters.eq("type", ProjectSubjectEvent.TYPE_KPI));
        query.sort = Sorts.descending("at");
        query.limit = MAX_KPI_STATE;
        var latest = new LinkedHashMap<String, String>();
        for (var event : eventCollection.find(query)) {
            if (event.key != null) latest.putIfAbsent(event.key, event.value);
        }
        return latest;
    }

    private int applyUpdates(String projectId, String subjectId, String output) {
        if (output == null || output.isBlank()) return 0;
        int count = 0;
        try {
            var parsed = JsonUtil.toMap(output);
            if (parsed == null) return 0;
            count += applyStatus(projectId, subjectId, parsed.get("status"));
            count += applyKpis(projectId, subjectId, parsed.get("kpis"));
            count += applyActionItems(projectId, subjectId, parsed.get("action_items"));
            count += applyNotes(projectId, subjectId, parsed.get("notes"));
            count += applyProfile(subjectId, parsed.get("profile"));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to apply subject analysis output, projectId={}, subjectId={}, error={}",
                projectId, subjectId, e.getMessage());
        }
        return count;
    }

    private int applyStatus(String projectId, String subjectId, Object statusValue) {
        if (!(statusValue instanceof Map<?, ?> statusMap)) return 0;
        var phase = str(statusMap.get("phase"));
        var summary = str(statusMap.get("summary"));
        if (phase == null && summary == null) return 0;
        stateService.updateStatus(projectId, subjectId, phase, summary, parseAt(statusMap.get("at")), WRITER);
        return 1;
    }

    private int applyKpis(String projectId, String subjectId, Object kpisValue) {
        if (!(kpisValue instanceof List<?> kpiList)) return 0;
        int count = 0;
        for (var item : kpiList) {
            if (!(item instanceof Map<?, ?> kpi)) continue;
            var key = str(kpi.get("key"));
            var value = str(kpi.get("value"));
            if (key == null || value == null) continue;
            stateService.recordKpi(projectId, subjectId, WRITER, parseAt(kpi.get("at")),
                new ProjectStateService.KpiSnapshot(key, value, str(kpi.get("unit"))));
            count++;
        }
        return count;
    }

    private int applyActionItems(String projectId, String subjectId, Object itemsValue) {
        if (!(itemsValue instanceof List<?> actionList)) return 0;
        int count = 0;
        for (var item : actionList) {
            if (!(item instanceof Map<?, ?> action)) continue;
            var title = str(action.get("title"));
            if (title == null) continue;
            stateService.updateActionItem(projectId, WRITER, new ProjectStateService.ActionItemFields(
                subjectId, str(action.get("id")), title, str(action.get("status")), null, parseAt(action.get("at"))));
            count++;
        }
        return count;
    }

    private int applyNotes(String projectId, String subjectId, Object notesValue) {
        if (!(notesValue instanceof List<?> noteList)) return 0;
        int count = 0;
        for (var note : noteList) {
            var content = str(note instanceof Map<?, ?> noteMap ? noteMap.get("content") : note);
            if (content == null) continue;
            var at = note instanceof Map<?, ?> noteMap ? parseAt(noteMap.get("at")) : null;
            stateService.addNote(projectId, subjectId, content, at, WRITER);
            count++;
        }
        return count;
    }

    // stable facts about the subject itself; stored as JSON text on the subject. Whether to
    // extract/update it is the LLM's call: the current profile is exposed in CURRENT STATE and the
    // prompt instructs it to keep the existing one (no program-side gating).
    private int applyProfile(String subjectId, Object profileValue) {
        if (!(profileValue instanceof Map<?, ?> profile) || profile.isEmpty()) return 0;
        subjectCollection.update(Filters.eq("_id", subjectId), Updates.combine(
            Updates.set("profile", JsonUtil.toJson(profile)),
            Updates.set("updated_at", ZonedDateTime.now())));
        return 1;
    }

    // the analyzer reports the MATERIAL time (yyyy-MM-dd or ISO) of each fact; invalid values
    // fall back to null so the write surface uses the write time
    private ZonedDateTime parseAt(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        var text = value.toString().trim();
        try {
            return ZonedDateTime.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            return parseDateOnly(text);
        }
    }

    private ZonedDateTime parseDateOnly(String text) {
        try {
            return java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneId.systemDefault());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private String str(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String limit(String value, int maxChars) {
        if (value == null) return "";
        return value.length() > maxChars ? value.substring(0, maxChars) + "...(truncated)" : value;
    }

    public record SubjectAnalysisResult(int consumed, int updated) {
    }
}
