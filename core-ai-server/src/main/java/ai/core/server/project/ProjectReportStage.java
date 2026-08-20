package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectReportDraft;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.file.FileService;
import ai.core.server.run.AgentRunner;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Report stage of the project feature (v1.4): renders ONE subject's event history into a
 * self-contained HTML campaign report. The renderer is an AGENT (a single LLM call cannot emit a
 * full report): the stage submits the builtin {@code project-report-renderer} agent run, which
 * writes the report section by section through the append_report_section tool into a draft
 * document; {@link ProjectReportCompletionJob} then assembles the sections into the final
 * artifact. The report is SUBJECT-level and user-triggered ONLY (analysis never auto-renders).
 *
 * @author stephen
 */
public class ProjectReportStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectReportStage.class);
    static final int MAX_EVENTS = 2000;
    static final int MAX_ACTIVITY_ENTRIES = 50;
    static final int MAX_NOTES = 50;
    static final int MAX_PHASES = 100;
    static final int MAX_KPIS = 200;
    static final int MAX_ACTIONS = 100;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectEvent> eventCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    MongoCollection<ProjectReportDraft> draftCollection;
    @Inject
    AgentRunner agentRunner;
    @Inject
    FileService fileService;
    @Inject
    ProjectQueryService queryService;

    // submits the renderer agent run and marks the subject in flight; throws on user-actionable
    // failures (already rendering / no events / definition missing)
    public void submit(String projectId, String subjectId) {
        var project = projectCollection.get(projectId).orElse(null);
        if (project == null || ProjectService.STATUS_ARCHIVED.equals(project.status)) return;
        var subject = subjectCollection.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) return;
        if (subject.reportRunId != null) {
            complete(subjectId);
            var current = subjectCollection.get(subjectId).orElse(null);
            if (current != null && current.reportRunId != null) {
                throw new IllegalStateException("a report render is already running for this subject");
            }
        }
        var events = loadEvents(projectId, subjectId);
        if (events.isEmpty()) {
            throw new IllegalStateException("subject has no events yet — run an analysis first");
        }
        var definition = agentCollection.get("builtin-" + ProjectBuiltinAgents.REPORT_RENDERER).orElse(null);
        if (definition == null) {
            throw new IllegalStateException("report renderer definition is missing; reset builtin agents to restore it");
        }
        var draft = new ProjectReportDraft();
        draft.id = UUID.randomUUID().toString();
        draft.subjectId = subjectId;
        draft.sections = new ArrayList<>();
        draft.createdAt = ZonedDateTime.now();
        draft.updatedAt = draft.createdAt;
        draftCollection.insert(draft);
        var input = buildInput(project, subject, events);
        String runId;
        try {
            runId = agentRunner.runAs(definition, input, TriggerType.MANUAL, project.userId,
                Map.of("project_id", projectId, "subject_id", subjectId, "draft_id", draft.id));
        } catch (RuntimeException e) {
            draftCollection.delete(Filters.eq("_id", draft.id));
            throw e;
        }
        subjectCollection.update(Filters.eq("_id", subjectId), Updates.combine(
            Updates.set("report_run_id", runId),
            Updates.set("report_draft_id", draft.id),
            Updates.set("report_error", null),
            Updates.set("updated_at", ZonedDateTime.now())));
        LOGGER.info("report render submitted, subjectId={}, runId={}, draftId={}", subjectId, runId, draft.id);
    }

    // called by the completion job (and on submit retry): assembles the draft when the agent run
    // finished, or records the failure; no-op while the run is still in flight
    public void complete(String subjectId) {
        var subject = subjectCollection.get(subjectId).orElse(null);
        if (subject == null || subject.reportRunId == null) return;
        var run = agentRunCollection.get(subject.reportRunId).orElse(null);
        if (run == null || run.status == null) {
            finish(subject, "report render run not found, runId=" + subject.reportRunId, null);
            return;
        }
        if (run.status == RunStatus.RUNNING || run.status == RunStatus.PENDING || run.status == RunStatus.PAUSED) return;
        if (run.status == RunStatus.COMPLETED) {
            assemble(subject);
            return;
        }
        var detail = run.status.name() + (run.output != null && !run.output.isBlank() ? ": " + run.output : "");
        finish(subject, "report render " + detail, null);
    }

    private void assemble(ProjectSubject subject) {
        var draft = subject.reportDraftId != null ? draftCollection.get(subject.reportDraftId).orElse(null) : null;
        var html = draft != null && draft.sections != null && !draft.sections.isEmpty()
            ? String.join("", draft.sections) : null;
        if (html == null || html.isBlank()) {
            finish(subject, "report render produced no sections", null);
            return;
        }
        var eventsThrough = latestEventAt(subject);
        var record = saveReport(subject, html);
        subjectCollection.update(Filters.eq("_id", subject.id), Updates.combine(
            Updates.set("report_file_id", record.fileId),
            Updates.set("report_share_token", record.shareToken),
            Updates.set("report_generated_at", ZonedDateTime.now()),
            Updates.set("report_events_at", eventsThrough),
            Updates.set("report_error", null),
            Updates.set("updated_at", ZonedDateTime.now()),
            Updates.unset("report_run_id"),
            Updates.unset("report_draft_id")));
        draftCollection.delete(Filters.eq("_id", draft.id));
        LOGGER.info("report assembled, subjectId={}", subject.id);
    }

    private void finish(ProjectSubject subject, String error, String draftId) {
        var updates = new ArrayList<org.bson.conversions.Bson>();
        updates.add(Updates.set("report_error", error));
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));
        updates.add(Updates.unset("report_run_id"));
        updates.add(Updates.unset("report_draft_id"));
        subjectCollection.update(Filters.eq("_id", subject.id), Updates.combine(updates));
        var draft = draftId != null ? draftId : subject.reportDraftId;
        if (draft != null) draftCollection.delete(Filters.eq("_id", draft));
        LOGGER.info("report render finished with error, subjectId={}, error={}", subject.id, error);
    }

    private ZonedDateTime latestEventAt(ProjectSubject subject) {
        var query = new Query();
        query.filter = Filters.eq("subject_id", subject.id);
        query.sort = Sorts.descending("at");
        query.limit = 1;
        var events = eventCollection.find(query);
        return events.isEmpty() ? ZonedDateTime.now() : events.getFirst().at;
    }

    private ReportFile saveReport(ProjectSubject subject, String html) {
        var project = projectCollection.get(subject.projectId).orElse(null);
        var userId = project != null ? project.userId : subject.userId;
        Path tempFile;
        try {
            tempFile = Files.createTempFile("subject-report-", ".html");
            Files.write(tempFile, html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write report html to temp file", e);
        }
        var fileName = "subject-report-" + TIME_FORMAT.format(ZonedDateTime.now()).replace(' ', '-') + ".html";
        var record = fileService.upload(userId, fileName, "text/html", tempFile);
        var shared = fileService.share(record.id, userId);
        return new ReportFile(record.id, shared.shareToken);
    }

    private List<ProjectSubjectEvent> loadEvents(String projectId, String subjectId) {
        var filter = Filters.and(Filters.eq("project_id", projectId), Filters.eq("subject_id", subjectId));
        long total = eventCollection.count(filter);
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.ascending("at");
        if (total > MAX_EVENTS) query.skip = (int) (total - MAX_EVENTS);
        query.limit = MAX_EVENTS;
        return eventCollection.find(query);
    }

    private String buildInput(Project project, ProjectSubject subject, List<ProjectSubjectEvent> events) {
        var input = new StringBuilder(16384);
        input.append("SUBJECT: ").append(subject.name);
        if (subject.description != null && !subject.description.isBlank()) input.append(" — ").append(subject.description);
        input.append('\n');
        if (project.goal != null && !project.goal.isBlank()) input.append("PROJECT GOAL: ").append(project.goal).append('\n');
        if (project.playbook != null && !project.playbook.isBlank()) {
            input.append("PLAYBOOK:\n").append(limit(project.playbook, 8000)).append('\n');
        }
        var status = currentStatus(project, subject.id);
        if (status != null) input.append("CURRENT STATUS: ").append(status).append('\n');
        input.append("\nEVENT HISTORY (authoritative):\n")
            .append(foldEvents(events))
            .append("\nRECENT ACTIVITY (attributed sessions/reports):\n");
        appendActivity(input, project.id, subject.id);
        input.append("\nWrite the report section by section via append_report_section now.");
        return input.toString();
    }

    // deterministic fold of one subject's events: one ordered block per event kind — the renderer
    // narrates, never aggregates. Phase durations are precomputed so the renderer can draw the
    // timeline (start → end, days in phase) without any math of its own.
    private String foldEvents(List<ProjectSubjectEvent> events) {
        var phases = new ArrayList<PhasePoint>();
        var summaries = new ArrayList<String>();
        var kpis = new LinkedHashMap<String, List<String>>();
        var actions = new LinkedHashMap<String, List<String>>();
        var notes = new ArrayList<String>();
        var statuses = new ArrayList<String>();
        for (var event : events) {
            var time = event.at != null ? TIME_FORMAT.format(event.at) : "?";
            switch (event.type) {
                case ProjectSubjectEvent.TYPE_PHASE -> phases.add(new PhasePoint(event.at, event.value));
                case ProjectSubjectEvent.TYPE_SUMMARY -> summaries.add(time + ": " + event.value);
                case ProjectSubjectEvent.TYPE_KPI -> kpis.computeIfAbsent(event.key, key -> new ArrayList<>())
                    .add(event.value + " (" + time + ")");
                case ProjectSubjectEvent.TYPE_ACTION_ITEM -> actions.computeIfAbsent(actionTitle(event), key -> new ArrayList<>())
                    .add(event.value + " (" + time + ")");
                case ProjectSubjectEvent.TYPE_NOTE -> notes.add(time + ": " + event.value);
                case ProjectSubjectEvent.TYPE_SUBJECT_STATUS -> statuses.add(time + " " + event.value);
                default -> { /* unknown event type: ignored */ }
            }
        }
        var digest = new StringBuilder(2048);
        appendPhaseHistory(digest, phases, MAX_PHASES);
        appendList(digest, "summaries", summaries, MAX_PHASES);
        appendList(digest, "tracking status", statuses, MAX_PHASES);
        appendSeries(digest, "KPIs", kpis, MAX_KPIS);
        appendSeries(digest, "action items", actions, MAX_ACTIONS);
        appendList(digest, "notes", notes, MAX_NOTES);
        return digest.toString();
    }

    private void appendPhaseHistory(StringBuilder digest, List<PhasePoint> phases, int max) {
        if (phases.isEmpty()) return;
        digest.append("- phase history (start -> end: phase, duration):\n");
        for (int i = 0; i < max && i < phases.size(); i++) {
            var point = phases.get(i);
            var start = point.at != null ? TIME_FORMAT.format(point.at) : "?";
            String end = "now";
            PhasePoint next = i + 1 < phases.size() ? phases.get(i + 1) : null;
            if (next != null && next.at != null) end = TIME_FORMAT.format(next.at);
            digest.append("  ").append(start).append(" -> ").append(end)
                .append(": \"").append(point.phase).append("\" (")
                .append(durationDays(point.at, next != null ? next.at : null)).append(")\n");
        }
    }

    private String durationDays(ZonedDateTime from, ZonedDateTime to) {
        if (from == null) return "?";
        var end = to != null ? to : ZonedDateTime.now();
        long days = java.time.Duration.between(from, end).toDays();
        return days <= 0 ? "<1 day" : days + " days";
    }

    private String actionTitle(ProjectSubjectEvent event) {
        if (event.meta == null || event.meta.isBlank()) return event.key;
        try {
            var meta = JsonUtil.toMap(event.meta);
            var title = meta != null ? meta.get("title") : null;
            return title != null ? title.toString() : event.key;
        } catch (RuntimeException e) {
            return event.key;
        }
    }

    private void appendList(StringBuilder digest, String label, List<String> rows, int max) {
        if (rows.isEmpty()) return;
        digest.append("- ").append(label).append(":\n");
        for (var row : rows.size() > max ? rows.subList(rows.size() - max, rows.size()) : rows) {
            digest.append("  ").append(row).append('\n');
        }
    }

    private void appendSeries(StringBuilder digest, String label, Map<String, List<String>> series, int max) {
        if (series.isEmpty()) return;
        digest.append("- ").append(label).append(":\n");
        int count = 0;
        for (var entry : series.entrySet()) {
            if (count++ >= max) break;
            var values = entry.getValue();
            var tail = values.size() > 20 ? values.subList(values.size() - 20, values.size()) : values;
            digest.append("  ").append(entry.getKey()).append(": ").append(String.join(" -> ", tail)).append('\n');
        }
    }

    private void appendActivity(StringBuilder input, String projectId, String subjectId) {
        int count = 0;
        for (var entry : queryService.timeline(projectId, subjectId)) {
            if (!"session".equals(entry.type()) && !"report".equals(entry.type())) continue;
            var time = entry.at() != null ? TIME_FORMAT.format(entry.at()) : "?";
            input.append("  [").append(entry.type()).append("] ").append(time).append(' ')
                .append(entry.title()).append('\n');
            if (++count >= MAX_ACTIVITY_ENTRIES) break;
        }
    }

    private String currentStatus(Project project, String subjectId) {
        if (project.subjectStatuses == null) return null;
        for (var status : project.subjectStatuses) {
            if (subjectId.equals(status.subjectId)) {
                var phase = status.phase != null ? status.phase : "";
                var summary = status.summary != null ? " — " + status.summary : "";
                return phase + summary;
            }
        }
        return null;
    }

    private String limit(String value, int maxChars) {
        return value != null && value.length() > maxChars ? value.substring(0, maxChars) + "...(truncated)" : value;
    }

    private record PhasePoint(ZonedDateTime at, String phase) {
    }

    private record ReportFile(String fileId, String shareToken) {
    }
}
