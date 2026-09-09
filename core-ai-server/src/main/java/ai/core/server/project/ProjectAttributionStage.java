package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
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
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Attribution stage of the project analysis pipeline (high-frequency): offers the members' execution
 * records to the tunable {@code project-attributor} LLM_CALL definition and applies the result.
 * Collection is TWO-pass and convergent:
 * <ol>
 *   <li>FRESH: the newest records of every type, so new material never waits behind history;</li>
 *   <li>FORWARD: the oldest records after the cursor ({@code attribution_backfilled_at}), batch by
 *       batch; the cursor advances to the newest record covered, so history is walked exactly once
 *       and, once caught up, the forward pass simply picks up whatever arrived since.</li>
 * </ol>
 * Every offered record gets a scan marker ({@link ProjectTargetScanStore}) whether the attributor
 * attributed it or skipped it, so a record costs at most one LLM pass — until it GROWS (a session
 * with new messages), in which case it is offered again and its attribution rows are re-opened for
 * analysis. Failures leave cursor and markers untouched so the next run retries.
 *
 * @author stephen
 */
public class ProjectAttributionStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAttributionStage.class);
    // runs carry dense, short input (a run usually names its subject explicitly), so they are
    // collected BEFORE sessions — sessions' transcripts blow the digest cap and used to starve runs.
    // Attribution only needs the USER's input (what the user asked about), so assistant replies and
    // run outputs are omitted. The digest is kept small on purpose: a smaller input means a shorter
    // reasoning silence, which avoids the upstream idle timeout that cancels long-silent SSE streams.
    static final int MAX_SESSIONS = 20;
    static final int MAX_MESSAGES_PER_SESSION = 10;
    static final int MAX_RUNS = 30;
    static final int MAX_WORKFLOW_RUNS = 10;
    static final int MAX_DIGEST_CHARS = 80000;   // hard cap on the attribution input size
    static final ZonedDateTime EPOCH = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));

    @Inject
    MongoCollection<Project> projectCollection;
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
    ProjectService projectService;
    @Inject
    ProjectTargetScanStore scanStore;
    @Inject
    LLMCallExecutor llmCallExecutor;

    // returns how many targets were attributed (0 when there is no new material or nothing matched)
    public int run(String projectId) {
        var project = projectCollection.get(projectId).orElse(null);
        if (project == null) return 0;
        var cursor = project.attributionBackfilledAt != null ? project.attributionBackfilledAt : EPOCH;
        var material = new ProjectAttributionMaterial(projectId, MAX_DIGEST_CHARS);
        collect(material, project, null);      // fresh pass: newest records regardless of the cursor
        collect(material, project, cursor);    // forward pass: oldest records at/after the cursor
        if (material.isEmpty()) {
            LOGGER.info("no new unattributed material, projectId={}", projectId);
            advanceCursor(projectId, material.forwardLatest());
            return 0;
        }
        var definition = agentCollection.get("builtin-" + ProjectBuiltinAgents.ATTRIBUTOR).orElse(null);
        if (definition == null) {
            throw new IllegalStateException("attribution writer definition is missing; reset builtin agents to restore it");
        }
        var input = "SUBJECTS:\n" + subjectsText(projectId) + "\nNEW MATERIAL:\n" + material.digest();
        var output = llmCallExecutor.execute(definition, input, null, 900).output();
        var count = applyAttributions(projectId, output, material);
        // markers first (they make the run idempotent), then the cursor
        scanStore.markOffered(projectId, material.offered());
        reopenGrownTargets(projectId, material);
        advanceCursor(projectId, material.forwardLatest());
        LOGGER.info("project attribution applied, projectId={}, offered={}, attributed={}, cursor={}",
            projectId, material.offered().size(), count, material.forwardLatest());
        return count;
    }

    private void collect(ProjectAttributionMaterial material, Project project, ZonedDateTime cursor) {
        collectRuns(material, project, cursor);
        collectWorkflowRuns(material, project, cursor);
        collectSessions(material, project, cursor);
    }

    // fresh mode (cursor == null) walks newest → oldest; forward mode walks oldest → newest from the cursor
    private void collectRuns(ProjectAttributionMaterial material, Project project, ZonedDateTime cursor) {
        var agentIds = memberIds(project, "agent");
        if (agentIds.isEmpty()) return;
        var query = query(Filters.in("agent_id", agentIds), "started_at", cursor, MAX_RUNS);
        var runs = agentRunCollection.find(query);
        var states = scanStore.states(material.projectId(), ProjectAttributionStore.TARGET_RUN, runs.stream().map(r -> r.id).toList());
        for (var run : runs) {
            var decision = material.consider(ProjectAttributionStore.TARGET_RUN, run.id, run.startedAt, states.get(run.id), cursor != null);
            if (decision == ProjectAttributionMaterial.Decision.STOP) return;
            if (decision == ProjectAttributionMaterial.Decision.SKIP) continue;
            material.append("## run " + run.id + " (agent: " + run.agentId + ")\ninput: " + limit(run.input, 800) + "\n");
            for (var artifact : artifactsOf(run.artifacts != null ? run.artifacts : List.of())) material.addFile(artifact);
        }
    }

    private void collectWorkflowRuns(ProjectAttributionMaterial material, Project project, ZonedDateTime cursor) {
        var workflowIds = memberIds(project, "workflow");
        if (workflowIds.isEmpty()) return;
        var query = query(Filters.in("workflow_id", workflowIds), "started_at", cursor, MAX_WORKFLOW_RUNS);
        var runs = workflowRunCollection.find(query);
        var states = scanStore.states(material.projectId(), ProjectAttributionStore.TARGET_WORKFLOW_RUN, runs.stream().map(r -> r.id).toList());
        for (var run : runs) {
            var decision = material.consider(ProjectAttributionStore.TARGET_WORKFLOW_RUN, run.id, run.startedAt, states.get(run.id), cursor != null);
            if (decision == ProjectAttributionMaterial.Decision.STOP) return;
            if (decision == ProjectAttributionMaterial.Decision.SKIP) continue;
            material.append("## workflow run " + run.id + " (workflow: " + run.workflowId + ")\ninput: " + limit(run.input, 1000) + "\n");
        }
    }

    private void collectSessions(ProjectAttributionMaterial material, Project project, ZonedDateTime cursor) {
        var agentIds = memberIds(project, "agent");
        if (agentIds.isEmpty()) return;
        var query = query(Filters.in("agent_id", agentIds), "last_message_at", cursor, MAX_SESSIONS);
        var sessions = chatSessionCollection.find(query);
        var states = scanStore.states(material.projectId(), ProjectAttributionStore.TARGET_SESSION, sessions.stream().map(s -> s.id).toList());
        for (var session : sessions) {
            var decision = material.consider(ProjectAttributionStore.TARGET_SESSION, session.id, session.lastMessageAt, states.get(session.id), cursor != null);
            if (decision == ProjectAttributionMaterial.Decision.STOP) return;
            if (decision == ProjectAttributionMaterial.Decision.SKIP) continue;
            var text = new StringBuilder(1024);
            text.append("## session ").append(session.id).append(" (agent: ").append(session.agentId)
                .append(", title: ").append(session.title).append(")\n");
            var history = history(session.id);
            var tail = history.size() > MAX_MESSAGES_PER_SESSION
                ? history.subList(history.size() - MAX_MESSAGES_PER_SESSION, history.size()) : history;
            for (var message : tail) {
                if (message.role == null || !"user".equals(message.role)) continue;   // assistant replies carry no attribution signal
                if (message.content == null || message.content.isBlank()) continue;
                text.append("USER: ").append(limit(message.content, 400)).append('\n');
            }
            material.append(text.toString());
            for (var artifact : artifactsOf(session.artifacts != null ? session.artifacts : List.of())) material.addFile(artifact);
        }
    }

    private Query query(Bson memberFilter, String timeField, ZonedDateTime cursor, int limit) {
        var query = new Query();
        query.filter = cursor == null ? memberFilter : Filters.and(memberFilter, Filters.gte(timeField, cursor));
        query.sort = cursor == null ? Sorts.descending(timeField) : Sorts.ascending(timeField);
        query.limit = limit;
        return query;
    }

    private void advanceCursor(String projectId, ZonedDateTime next) {
        if (next == null) return;
        projectCollection.update(Filters.eq("_id", projectId), Updates.set("attribution_backfilled_at", next));
    }

    // a target that grew since its last offer (new messages) has new material for the subjects it is
    // attributed to: re-open its attribution rows so the subject analysis consumes the new part
    private void reopenGrownTargets(String projectId, ProjectAttributionMaterial material) {
        for (var target : material.grown()) {
            attributionCollection.update(Filters.and(
                    Filters.eq("target_type", target.targetType()),
                    Filters.eq("target_id", target.targetId()),
                    Filters.eq("project_id", projectId)),
                Updates.unset("analyzed_at"));
        }
    }

    private int applyAttributions(String projectId, String output, ProjectAttributionMaterial material) {
        if (output == null || output.isBlank()) return 0;
        int count = 0;
        try {
            var parsed = JsonUtil.toMap(output);
            var attributions = parsed != null ? parsed.get("attributions") : null;
            if (attributions instanceof List<?> list) {
                for (var item : list) {
                    if (!(item instanceof Map<?, ?> entry)) continue;
                    var targetType = str(entry.get("target_type"));
                    var targetId = str(entry.get("target_id"));
                    var subjectId = str(entry.get("subject_id"));
                    if (targetType == null || targetId == null || subjectId == null) continue;
                    if (!material.contains(targetType, targetId)) continue;   // only attribute targets the writer actually saw
                    count += attributeOne(projectId, subjectId, targetType, targetId);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to parse attribution output, projectId={}, error={}", projectId, e.getMessage());
        }
        return count;
    }

    private int attributeOne(String projectId, String subjectId, String targetType, String targetId) {
        try {
            if (!targetExists(targetType, targetId)) {
                LOGGER.warn("attribution skipped, unknown target, type={}, targetId={}", targetType, targetId);
                return 0;
            }
            projectService.attribute(projectId, subjectId, targetType, targetId, ProjectAttributionStore.SOURCE_ATTRIBUTOR);
            return 1;
        } catch (RuntimeException e) {
            LOGGER.warn("attribution rejected, subjectId={}, type={}, targetId={}, error={}",
                subjectId, targetType, targetId, e.getMessage());
            return 0;
        }
    }

    private boolean targetExists(String targetType, String targetId) {
        return switch (targetType) {
            case ProjectAttributionStore.TARGET_SESSION -> chatSessionCollection.get(targetId).isPresent();
            case ProjectAttributionStore.TARGET_RUN -> agentRunCollection.get(targetId).isPresent();
            case ProjectAttributionStore.TARGET_WORKFLOW_RUN -> workflowRunCollection.get(targetId).isPresent();
            case ProjectAttributionStore.TARGET_FILE -> fileRecordCollection.get(targetId).isPresent();
            default -> false;
        };
    }

    private String subjectsText(String projectId) {
        var text = new StringBuilder(256);
        for (var subject : projectService.subjects(projectId)) {
            text.append("- ").append(subject.id).append(": ").append(subject.name).append('\n');
        }
        return text.length() == 0 ? "(none)\n" : text.toString();
    }

    private List<String> artifactsOf(List<ai.core.server.domain.AgentRunArtifact> artifacts) {
        return artifacts.stream().map(a -> a.fileId).filter(id -> id != null).toList();
    }

    private List<ChatMessage> history(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.ascending("seq");
        return chatMessageCollection.find(query);
    }

    private List<String> memberIds(Project project, String type) {
        if (project.members == null) return List.of();
        return project.members.stream().filter(m -> type.equals(m.type)).map(m -> m.id).toList();
    }

    private String str(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String limit(String value, int maxChars) {
        if (value == null) return "";
        return value.length() > maxChars ? value.substring(0, maxChars) + "...(truncated)" : value;
    }
}
