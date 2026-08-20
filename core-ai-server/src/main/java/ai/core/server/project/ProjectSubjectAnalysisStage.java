package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.WorkflowRun;
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
import java.util.List;
import java.util.Map;

/**
 * Subject-analysis stage of the project analysis pipeline (low-frequency): takes the subject's
 * attributed-but-not-yet-analyzed material (attribution rows with a null consumption marker),
 * runs the tunable {@code project-subject-analyzer} LLM_CALL definition, applies the derived
 * status/KPIs/action items/notes and marks the consumed attributions as analyzed (per-attribution
 * cursor, so the same material is never analyzed twice).
 *
 * @author stephen
 */
public class ProjectSubjectAnalysisStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSubjectAnalysisStage.class);
    static final int MAX_SESSIONS = 20;
    static final int MAX_MESSAGES_PER_SESSION = 50;
    static final int MAX_RUNS = 10;
    static final int MAX_REPORTS = 10;
    static final int REPORT_MAX_CHARS = 16000;
    static final String WRITER = "project-agent";

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    MongoCollection<WorkflowRun> workflowRunCollection;
    @Inject
    MongoCollection<FileRecord> fileRecordCollection;
    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    ProjectStateService stateService;
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
        var material = gather(unanalyzed);
        if (material.digest.length() == 0) {
            markAnalyzed(unanalyzed, subject);   // nothing readable but consumed anyway (cursor progress)
            return new SubjectAnalysisResult(unanalyzed.size(), 0);
        }
        var definition = writerDefinition(ProjectBuiltinAgents.SUBJECT_ANALYZER);
        if (definition == null) {
            throw new IllegalStateException("subject analyzer definition is missing; reset builtin agents to restore it");
        }
        var input = buildInput(projectId, subject, material);
        // explicit long timeout: reasoning models stay silent for minutes while thinking and the
        // default call timeout has been observed to cut such streams short
        var output = llmCallExecutor.execute(definition, input, null, 900).output();
        var count = applyUpdates(projectId, subjectId, output);
        markAnalyzed(unanalyzed, subject);
        LOGGER.info("subject analysis applied, subjectId={}, consumed={}, updated={}", subjectId, unanalyzed.size(), count);
        return new SubjectAnalysisResult(unanalyzed.size(), count);
    }

    private org.bson.conversions.Bson unanalyzedFilter(String subjectId) {
        return Filters.and(
            Filters.eq("subject_id", subjectId),
            Filters.or(Filters.exists("analyzed_at", false), Filters.eq("analyzed_at", null)));
    }

    private List<ProjectSubjectAttribution> unanalyzedRows(String subjectId) {
        var query = new Query();
        query.filter = unanalyzedFilter(subjectId);
        query.limit = 100;
        return attributionCollection.find(query);
    }

    private Material gather(List<ProjectSubjectAttribution> unanalyzed) {
        var material = new Material();
        var sessions = targets(unanalyzed, "session");
        if (!sessions.isEmpty()) appendSessions(material, sessions);
        var runs = targets(unanalyzed, "run");
        if (!runs.isEmpty()) {
            var query = new Query();
            query.filter = Filters.in("_id", runs);
            query.limit = MAX_RUNS;
            for (var run : agentRunCollection.find(query)) {
                material.digest.append("## run ").append(run.id)
                    .append(" (agent: ").append(run.agentId)
                    .append(", at: ").append(formatTime(run.startedAt)).append(")\ninput: ")
                    .append(limit(run.input, 1000)).append("\noutput: ")
                    .append(limit(run.output, 3000)).append('\n');
            }
        }
        var files = targets(unanalyzed, "file");
        if (!files.isEmpty()) {
            var query = new Query();
            query.filter = Filters.in("_id", files);
            query.limit = MAX_REPORTS;
            for (var file : fileRecordCollection.find(query)) {
                material.digest.append("## report ").append(file.id)
                    .append(" (").append(file.fileName)
                    .append(", created: ").append(formatTime(file.createdAt)).append(")\n");
                if (file.data != null) {
                    material.digest.append(limit(htmlToText(file.data), REPORT_MAX_CHARS)).append('\n');
                }
            }
        }
        return material;
    }

    private void appendSessions(Material material, List<String> sessionIds) {
        var query = new Query();
        query.filter = Filters.in("_id", sessionIds);
        query.sort = Sorts.descending("last_message_at");
        query.limit = MAX_SESSIONS;
        for (var session : chatSessionCollection.find(query)) {
            material.digest.append("## session ").append(session.id)
                .append(" (title: ").append(session.title)
                .append(", at: ").append(formatTime(session.lastMessageAt)).append(")\n");
            var history = history(session.id);
            var tail = history.size() > MAX_MESSAGES_PER_SESSION
                ? history.subList(history.size() - MAX_MESSAGES_PER_SESSION, history.size()) : history;
            for (var message : tail) {
                if (message.role == null || message.content == null || message.content.isBlank()) continue;
                material.digest.append(message.role.toUpperCase(java.util.Locale.ROOT)).append(": ")
                    .append(limit(message.content, 2000)).append('\n');
            }
        }
    }

    private List<String> targets(List<ProjectSubjectAttribution> rows, String targetType) {
        return rows.stream().filter(a -> targetType.equals(a.targetType)).map(a -> a.targetId).toList();
    }

    private void markAnalyzed(List<ProjectSubjectAttribution> rows, ProjectSubject subject) {
        var now = ZonedDateTime.now();
        for (var row : rows) {
            attributionCollection.update(Filters.eq("_id", row.id), Updates.set("analyzed_at", now));
        }
        subject.analyzedAt = now;
        subjectCollection.update(Filters.eq("_id", subject.id), Updates.combine(
            Updates.set("analyzed_at", now),
            Updates.set("updated_at", now)));
    }

    private String buildInput(String projectId, ProjectSubject subject, Material material) {
        var project = projectCollection.get(projectId).orElse(null);
        var playbook = project != null && project.playbook != null && !project.playbook.isBlank()
            ? "PLAYBOOK:\n" + limit(project.playbook, 20000) + "\n\n"
            : "";
        var description = subject.description != null && !subject.description.isBlank()
            ? " — " + subject.description : "";
        return playbook
            + "SUBJECT: " + subject.name + description
            + "\n\nCURRENT STATE:\n" + currentState(project, subject)
            + "\nATTRIBUTED MATERIAL:\n" + material.digest
            + "\n\nReturn the analysis JSON now.";
    }

    // everything the analyzer should already know about the subject: its profile (stable facts,
    // extracted once), the current phase/summary and the open KPIs/action items. The LLM must NOT
    // re-derive these from scratch — it updates them incrementally.
    private String currentState(Project project, ProjectSubject subject) {
        var digest = new StringBuilder(512);
        if (subject.profile != null && !subject.profile.isBlank()) {
            digest.append("- profile: ").append(limit(subject.profile, 2000)).append('\n');
        }
        if (project != null && project.subjectStatuses != null) {
            for (var status : project.subjectStatuses) {
                if (subject.id.equals(status.subjectId)) {
                    digest.append("- phase: ").append(limit(status.phase, 200)).append("\n- summary: ")
                        .append(limit(status.summary, 500)).append('\n');
                }
            }
        }
        if (project != null && project.kpis != null) {
            for (var i = project.kpis.size() - 1; i >= 0; i--) {
                var kpi = project.kpis.get(i);
                if (!subject.id.equals(kpi.subjectId)) continue;
                digest.append("- kpi ").append(kpi.key).append(" = ").append(kpi.value).append('\n');
                if (digest.length() > 1500) break;
            }
        }
        if (project != null && project.actionItems != null) {
            for (var item : project.actionItems) {
                if (!subject.id.equals(item.subjectId) || "done".equals(item.status)) continue;
                digest.append("- action item ").append(item.id).append(": ")
                    .append(limit(item.title, 200)).append(" (").append(item.status).append(")\n");
            }
        }
        return digest.length() == 0 ? "(none)\n" : digest.toString();
    }

    // apply path used by the project-agent writer tool (the agent composes the input itself)
    public int apply(String projectId, String subjectId, String output) {
        return applyUpdates(projectId, subjectId, output);
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

    @SuppressWarnings("unchecked")
    private int applyStatus(String projectId, String subjectId, Object statusValue) {
        if (!(statusValue instanceof Map<?, ?> statusMap)) return 0;
        var phase = str(statusMap.get("phase"));
        var summary = str(statusMap.get("summary"));
        if (phase == null && summary == null) return 0;
        stateService.updateStatus(projectId, subjectId, phase, summary, parseAt(statusMap.get("at")), WRITER);
        return 1;
    }

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
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

    private String formatTime(ZonedDateTime time) {
        return time != null ? java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time) : "?";
    }

    // stable facts about the subject itself; stored as JSON text on the subject. Whether to
    // extract/update it is the LLM's call: the current profile is exposed in CURRENT STATE and the
    // prompt instructs it to keep the existing one (no program-side gating).
    @SuppressWarnings("unchecked")
    private int applyProfile(String subjectId, Object profileValue) {
        if (!(profileValue instanceof Map<?, ?> profile) || profile.isEmpty()) return 0;
        subjectCollection.update(Filters.eq("_id", subjectId), Updates.combine(
            Updates.set("profile", JsonUtil.toJson(profile)),
            Updates.set("updated_at", ZonedDateTime.now())));
        return 1;
    }

    private AgentDefinition writerDefinition(String nameKey) {
        return agentCollection.get("builtin-" + nameKey).orElse(null);
    }

    private List<ChatMessage> history(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.ascending("seq");
        return chatMessageCollection.find(query);
    }

    private String htmlToText(String base64Html) {
        try {
            var decoded = java.util.Base64.getDecoder().decode(base64Html);
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to decode report content for analysis", e);
            return "";
        }
    }

    private String str(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String limit(String value, int maxChars) {
        if (value == null) return "";
        return value.length() > maxChars ? value.substring(0, maxChars) + "...(truncated)" : value;
    }

    private static final class Material {
        final StringBuilder digest = new StringBuilder(8192);
    }

    public record SubjectAnalysisResult(int consumed, int updated) {
    }
}
