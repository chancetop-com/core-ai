package ai.core.server.project;

import ai.core.server.run.AgentRunQuery;

import ai.core.server.run.AgentRunStore;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.file.FileService;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the material behind a subject's unanalyzed attribution rows and folds it into ONE
 * chronological digest for the subject analyzer. Rows whose target is gone are consumed without
 * text; rows that do not fit into the per-type / total caps are NOT consumed and wait for the next
 * run. Report content is read through {@link FileService} (object storage or inline), HTML is
 * reduced to text; a grown session only contributes the messages newer than its last consumption.
 *
 * @author stephen
 */
public class ProjectAnalysisMaterialLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAnalysisMaterialLoader.class);
    static final int MAX_SESSIONS = 20;
    static final int MAX_MESSAGES_PER_SESSION = 50;
    static final int MAX_RUNS = 10;
    static final int MAX_WORKFLOW_RUNS = 10;
    static final int MAX_REPORTS = 10;
    static final int REPORT_MAX_CHARS = 16000;
    static final int MAX_DIGEST_CHARS = 200000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static String htmlToText(String html) {
        return html
            .replaceAll("<script[\\s\\S]*?</script>", " ")
            .replaceAll("<style[\\s\\S]*?</style>", " ")
            .replaceAll("<[^>]+>", " ")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("\\s+", " ")
            .trim();
    }

    @Inject
    ai.core.server.session.ChatSessionStore chatSessionStore;
    @Inject
    ai.core.server.session.ChatMessageStore chatMessageStore;
    @Inject
    AgentRunStore agentRunStore;
    @Inject
    ai.core.server.workflow.WorkflowRunStore workflowRunStore;
    @Inject
    ai.core.server.file.FileRecordStore fileRecordStore;
    @Inject
    FileService fileService;

    public Material load(List<ProjectSubjectAttribution> rows) {
        var items = new ArrayList<Item>();
        var consumed = new ArrayList<Consumed>();
        loadSessions(rows, items, consumed);
        loadRuns(rows, items, consumed);
        loadWorkflowRuns(rows, items, consumed);
        loadFiles(rows, items, consumed);
        items.sort((a, b) -> compareAsc(a.at, b.at));
        var counts = new HashMap<String, Integer>();
        var digest = new StringBuilder(8192);
        for (var item : items) {
            var used = counts.getOrDefault(item.row.targetType, 0);
            if (used >= cap(item.row.targetType)) continue;
            if (digest.length() + item.text.length() > MAX_DIGEST_CHARS) break;
            counts.put(item.row.targetType, used + 1);
            digest.append(item.text);
            consumed.add(new Consumed(item.row, item.at));
        }
        return new Material(digest.toString(), consumed);
    }

    private void loadSessions(List<ProjectSubjectAttribution> rows, List<Item> items, List<Consumed> consumed) {
        var byTarget = byTarget(rows, ProjectAttributionStore.TARGET_SESSION);
        if (byTarget.isEmpty()) return;
        var found = new HashMap<String, ChatSession>();
        var query = new ai.core.server.session.ChatSessionQuery();
        query.ids = new ArrayList<>(byTarget.keySet());
        query.limit = null;
        for (var session : chatSessionStore.find(query)) found.put(session.id, session);
        for (var entry : byTarget.entrySet()) {
            var session = found.get(entry.getKey());
            if (session == null) {
                consumed.add(new Consumed(entry.getValue(), null));
                continue;
            }
            var text = new StringBuilder(2048);
            text.append("## session ").append(session.id).append(" (title: ").append(session.title)
                .append(", at: ").append(formatTime(session.lastMessageAt)).append(")\n");
            var since = entry.getValue().analyzedThrough;
            var history = history(session.id).stream()
                .filter(m -> since == null || m.createdAt == null || m.createdAt.isAfter(since)).toList();
            var tail = history.size() > MAX_MESSAGES_PER_SESSION
                ? history.subList(history.size() - MAX_MESSAGES_PER_SESSION, history.size()) : history;
            for (var message : tail) {
                if (message.role == null || message.content == null || message.content.isBlank()) continue;
                text.append(message.role.toUpperCase(Locale.ROOT)).append(": ").append(limit(message.content, 2000)).append('\n');
            }
            items.add(new Item(entry.getValue(), session.lastMessageAt, text.toString()));
        }
    }

    private void loadRuns(List<ProjectSubjectAttribution> rows, List<Item> items, List<Consumed> consumed) {
        var byTarget = byTarget(rows, ProjectAttributionStore.TARGET_RUN);
        if (byTarget.isEmpty()) return;
        var found = new HashMap<String, AgentRun>();
        var runQuery = new AgentRunQuery();
        runQuery.ids = new ArrayList<>(byTarget.keySet());
        runQuery.limit = runQuery.ids.size();
        for (var run : agentRunStore.find(runQuery)) found.put(run.id, run);
        for (var entry : byTarget.entrySet()) {
            var run = found.get(entry.getKey());
            if (run == null) {
                consumed.add(new Consumed(entry.getValue(), null));
                continue;
            }
            var text = "## run " + run.id + " (agent: " + run.agentId + ", at: " + formatTime(run.startedAt) + ")\ninput: "
                + limit(run.input, 1000) + "\noutput: " + limit(run.output, 3000) + "\n";
            items.add(new Item(entry.getValue(), run.startedAt, text));
        }
    }

    private void loadWorkflowRuns(List<ProjectSubjectAttribution> rows, List<Item> items, List<Consumed> consumed) {
        var byTarget = byTarget(rows, ProjectAttributionStore.TARGET_WORKFLOW_RUN);
        if (byTarget.isEmpty()) return;
        var found = new HashMap<String, WorkflowRun>();
        for (var run : workflowRunStore.findByIds(new ArrayList<>(byTarget.keySet()))) found.put(run.id, run);
        for (var entry : byTarget.entrySet()) {
            var run = found.get(entry.getKey());
            if (run == null) {
                consumed.add(new Consumed(entry.getValue(), null));
                continue;
            }
            var text = "## workflow run " + run.id + " (workflow: " + run.workflowId + ", at: " + formatTime(run.startedAt) + ")\ninput: "
                + limit(run.input, 1000) + "\noutput: " + limit(run.output, 3000) + "\n";
            items.add(new Item(entry.getValue(), run.startedAt, text));
        }
    }

    private void loadFiles(List<ProjectSubjectAttribution> rows, List<Item> items, List<Consumed> consumed) {
        var byTarget = byTarget(rows, ProjectAttributionStore.TARGET_FILE);
        if (byTarget.isEmpty()) return;
        var query = new ai.core.server.file.FileRecordQuery();
        query.ids = new ArrayList<>(byTarget.keySet());
        query.limit = null;
        var found = new HashMap<String, FileRecord>();
        for (var file : fileRecordStore.find(query)) found.put(file.id, file);
        for (var entry : byTarget.entrySet()) {
            var file = found.get(entry.getKey());
            if (file == null) {
                consumed.add(new Consumed(entry.getValue(), null));
                continue;
            }
            var text = new StringBuilder(4096);
            text.append("## report ").append(file.id).append(" (").append(file.fileName)
                .append(", created: ").append(formatTime(file.createdAt)).append(")\n");
            var content = reportText(file);
            if (!content.isBlank()) text.append(limit(content, REPORT_MAX_CHARS)).append('\n');
            items.add(new Item(entry.getValue(), file.createdAt, text.toString()));
        }
    }

    // text-like reports only (HTML reduced to text); binary formats contribute their name and date
    private String reportText(FileRecord file) {
        var type = file.contentType != null ? file.contentType.toLowerCase(Locale.ROOT) : "";
        var name = file.fileName != null ? file.fileName.toLowerCase(Locale.ROOT) : "";
        var html = type.contains("html") || name.endsWith(".html") || name.endsWith(".htm");
        var textual = html || type.startsWith("text/") || type.contains("json") || type.contains("markdown")
            || name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".json");
        if (!textual) return "";
        try {
            var record = fileRecordStore.get(file.id).orElse(file);   // full record: inline data when not in object storage
            var raw = new String(fileService.getBytes(record), StandardCharsets.UTF_8);
            return html ? htmlToText(raw) : raw;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read report content for analysis, fileId={}, error={}", file.id, e.getMessage());
            return "";
        }
    }

    private Map<String, ProjectSubjectAttribution> byTarget(List<ProjectSubjectAttribution> rows, String targetType) {
        var result = new HashMap<String, ProjectSubjectAttribution>();
        for (var row : rows) {
            if (targetType.equals(row.targetType) && row.targetId != null) result.putIfAbsent(row.targetId, row);
        }
        return result;
    }

    private int cap(String targetType) {
        return switch (targetType) {
            case ProjectAttributionStore.TARGET_SESSION -> MAX_SESSIONS;
            case ProjectAttributionStore.TARGET_RUN -> MAX_RUNS;
            case ProjectAttributionStore.TARGET_WORKFLOW_RUN -> MAX_WORKFLOW_RUNS;
            case ProjectAttributionStore.TARGET_FILE -> MAX_REPORTS;
            default -> 0;
        };
    }

    private List<ChatMessage> history(String sessionId) {
        var query = new ai.core.server.session.ChatMessageQuery();
        query.sessionId = sessionId;
        query.limit = null;
        return chatMessageStore.find(query);
    }

    private int compareAsc(ZonedDateTime left, ZonedDateTime right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private String formatTime(ZonedDateTime time) {
        return time != null ? TIME_FORMAT.format(time) : "?";
    }

    private String limit(String value, int maxChars) {
        if (value == null) return "";
        return value.length() > maxChars ? value.substring(0, maxChars) + "...(truncated)" : value;
    }

    /** one attribution row actually fed to the analyzer, with the material time it was consumed through */
    public record Consumed(ProjectSubjectAttribution row, ZonedDateTime materialAt) {
    }

    public record Material(String digest, List<Consumed> consumed) {
    }

    private record Item(ProjectSubjectAttribution row, ZonedDateTime at, String text) {
    }
}







