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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attribution stage of the project analysis pipeline (high-frequency): assigns the members'
 * execution records to subjects. Collection is TWO-pass and incremental:
 * <ol>
 *   <li>BACKFILL: records older than the backfill watermark (records "older than this have been
 *       scanned") are forced through the writer batch by batch — legacy material is eventually
 *       covered even while new records keep arriving. On success the watermark advances to the
 *       oldest record seen this run; epoch = backfill complete.</li>
 *   <li>FRESH: the most recent unattributed records (no lower bound) so new material is never
 *       starved — records that still arrive above the watermark are caught here.</li>
 * </ol>
 * The tunable {@code project-attributor} LLM_CALL definition runs on the combined digest; the
 * resulting attributions are applied idempotently. Failures leave the watermark untouched so the
 * next run retries.
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
    static final ZonedDateTime FUTURE = ZonedDateTime.of(2099, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));   // backfill-complete sentinel

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
    LLMCallExecutor llmCallExecutor;

    // returns how many targets were attributed (0 when there is no new material or nothing matched).
    // Collection is TWO-pass:
    //   FRESH   — the most recent unattributed records, always processed first (new material is
    //             never starved while history is being backfilled);
    //   BACKFILL— from the OLDEST records toward the future, batch by batch. The watermark advances
    //             to the newest record seen in the batch; when no unattributed records remain above
    //             it the watermark is pinned to FUTURE (backfill complete) and the pass stops.
    public int run(String projectId) {
        var project = projectCollection.get(projectId).orElse(null);
        if (project == null) return 0;
        var from = project.attributionBackfilledAt != null ? project.attributionBackfilledAt : EPOCH;
        var backfillDone = !from.isBefore(FUTURE);
        var material = new Material();
        var attributed = attributedTargets(projectId);
        // fresh pass first: latest unattributed records regardless of age
        addRuns(material, project, null, attributed, false);
        addWorkflowRuns(material, project, null, attributed, false);
        addSessions(material, project, null, attributed, false);
        // backfill pass: the oldest unattributed records after the watermark, batch by batch
        if (!backfillDone) {
            addRuns(material, project, from, attributed, true);
            addWorkflowRuns(material, project, from, attributed, true);
            addSessions(material, project, from, attributed, true);
            if (material.backfillCount == 0) backfillDone = true;   // nothing older left → history covered
        }
        if (material.digest.length() == 0) {
            LOGGER.info("no new unattributed material, projectId={}", projectId);
            advanceWatermark(projectId, FUTURE);   // nothing left to scan → backfill complete
            return 0;
        }
        var definition = writerDefinition(ProjectBuiltinAgents.ATTRIBUTOR);
        if (definition == null) {
            throw new IllegalStateException("attribution writer definition is missing; reset builtin agents to restore it");
        }
        var input = "SUBJECTS:\n" + subjectsText(projectId) + "\nNEW MATERIAL:\n" + material.digest;
        var output = llmCallExecutor.execute(definition, input, null, 900).output();
        var count = applyAttributions(projectId, output, material);
        var next = backfillDone ? FUTURE : material.backfillLatest != null ? material.backfillLatest : FUTURE;
        advanceWatermark(projectId, next);
        LOGGER.info("project attribution applied, projectId={}, attributed={}, backfilledThrough={}",
            projectId, count, material.backfillLatest);
        return count;
    }

    private void advanceWatermark(String projectId, ZonedDateTime next) {
        projectCollection.update(Filters.eq("_id", projectId),
            Updates.set("attribution_backfilled_at", next));
    }

    // apply path used by the project-agent writer tool (the agent composes the input itself)
    public int apply(String projectId, String output) {
        return applyAttributions(projectId, output, null);
    }

    private int applyAttributions(String projectId, String output, Material material) {
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
                    if (material != null && !material.contains(targetType, targetId)) continue;   // only attribute targets the writer actually saw
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
            projectService.attribute(projectId, subjectId, targetType, targetId);
            return 1;
        } catch (RuntimeException e) {
            LOGGER.warn("attribution rejected, subjectId={}, type={}, targetId={}, error={}",
                subjectId, targetType, targetId, e.getMessage());
            return 0;
        }
    }

    private boolean targetExists(String targetType, String targetId) {
        return switch (targetType) {
            case "session" -> chatSessionCollection.get(targetId).isPresent();
            case "run" -> agentRunCollection.get(targetId).isPresent();
            case "workflow_run" -> workflowRunCollection.get(targetId).isPresent();
            case "file" -> fileRecordCollection.get(targetId).isPresent();
            default -> false;
        };
    }

    private AgentDefinition writerDefinition(String nameKey) {
        return agentCollection.get("builtin-" + nameKey).orElse(null);
    }

    private String subjectsText(String projectId) {
        var text = new StringBuilder(256);
        for (var subject : projectService.subjects(projectId)) {
            text.append("- ").append(subject.id).append(": ").append(subject.name).append('\n');
        }
        return text.length() == 0 ? "(none)\n" : text.toString();
    }

    private Set<String> attributedTargets(String projectId) {
        var subjectIds = projectService.subjects(projectId).stream().map(s -> s.id).toList();
        var targets = new LinkedHashSet<String>();
        if (subjectIds.isEmpty()) return targets;
        var query = new Query();
        query.filter = Filters.in("subject_id", subjectIds);
        for (var attribution : attributionCollection.find(query)) {
            targets.add(attribution.targetType + ":" + attribution.targetId);
        }
        return targets;
    }

    // fresh mode collects the most recent records (descending); backfill mode collects the oldest
    // records after the watermark (ascending) so history is covered from the beginning forward
    private void addSessions(Material material, Project project, ZonedDateTime watermark, Set<String> attributed, boolean backfill) {
        var agentIds = memberIds(project, "agent");
        if (agentIds.isEmpty()) return;
        var query = new Query();
        query.filter = backfill
            ? Filters.and(Filters.in("agent_id", agentIds), Filters.gt("last_message_at", watermark))
            : Filters.in("agent_id", agentIds);
        query.sort = backfill ? Sorts.ascending("last_message_at") : Sorts.descending("last_message_at");
        query.limit = MAX_SESSIONS;
        for (var session : chatSessionCollection.find(query)) {
            if (attributed.contains("session:" + session.id)) continue;
            if (material.sessionIds.contains(session.id)) continue;   // already collected by the other pass
            if (!material.canGrow()) return;
            material.sessionIds.add(session.id);
            if (backfill) material.trackBackfill(session.lastMessageAt);
            material.digest.append("## session ").append(session.id)
                .append(" (agent: ").append(session.agentId)
                .append(", title: ").append(session.title).append(")\n");
            var history = history(session.id);
            var tail = history.size() > MAX_MESSAGES_PER_SESSION
                ? history.subList(history.size() - MAX_MESSAGES_PER_SESSION, history.size()) : history;
            for (var message : tail) {
                if (message.role == null || !"user".equals(message.role)) continue;   // assistant replies carry no attribution signal
                if (message.content == null || message.content.isBlank()) continue;
                material.digest.append("USER: ").append(limit(message.content, 400)).append('\n');
            }
            for (var artifact : artifactsOf(session.artifacts != null ? session.artifacts : List.of())) {
                material.fileIds.add(artifact);
            }
        }
    }

    private void addRuns(Material material, Project project, ZonedDateTime watermark, Set<String> attributed, boolean backfill) {
        var agentIds = memberIds(project, "agent");
        if (agentIds.isEmpty()) return;
        var query = new Query();
        query.filter = backfill
            ? Filters.and(Filters.in("agent_id", agentIds), Filters.gt("started_at", watermark))
            : Filters.in("agent_id", agentIds);
        query.sort = backfill ? Sorts.ascending("started_at") : Sorts.descending("started_at");
        query.limit = MAX_RUNS;
        for (var run : agentRunCollection.find(query)) {
            if (attributed.contains("run:" + run.id)) continue;
            if (material.runIds.contains(run.id)) continue;
            if (!material.canGrow()) return;
            material.runIds.add(run.id);
            if (backfill) material.trackBackfill(run.startedAt);
            material.digest.append("## run ").append(run.id)
                .append(" (agent: ").append(run.agentId).append(")\ninput: ")
                .append(limit(run.input, 800)).append('\n');   // output omitted: execution details are not attribution signals
            for (var artifact : artifactsOf(run.artifacts != null ? run.artifacts : List.of())) {
                material.fileIds.add(artifact);
            }
        }
    }

    private void addWorkflowRuns(Material material, Project project, ZonedDateTime watermark, Set<String> attributed, boolean backfill) {
        var workflowIds = memberIds(project, "workflow");
        if (workflowIds.isEmpty()) return;
        var query = new Query();
        query.filter = backfill
            ? Filters.and(Filters.in("workflow_id", workflowIds), Filters.gt("started_at", watermark))
            : Filters.in("workflow_id", workflowIds);
        query.sort = backfill ? Sorts.ascending("started_at") : Sorts.descending("started_at");
        query.limit = MAX_WORKFLOW_RUNS;
        for (var run : workflowRunCollection.find(query)) {
            if (attributed.contains("workflow_run:" + run.id)) continue;
            if (material.workflowRunIds.contains(run.id)) continue;
            if (!material.canGrow()) return;
            material.workflowRunIds.add(run.id);
            if (backfill) material.trackBackfill(run.startedAt);
            material.digest.append("## workflow run ").append(run.id)
                .append(" (workflow: ").append(run.workflowId).append(")\ninput: ")
                .append(limit(run.input, 1000)).append('\n');
        }
    }

    private List<String> artifactsOf(List<ai.core.server.domain.AgentRunArtifact> artifacts) {
        var fileIds = new ArrayList<String>();
        for (var artifact : artifacts) {
            if (artifact.fileId != null) fileIds.add(artifact.fileId);
        }
        return fileIds;
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

    static final class Material {
        final Set<String> sessionIds = new LinkedHashSet<>();
        final Set<String> runIds = new LinkedHashSet<>();
        final Set<String> workflowRunIds = new LinkedHashSet<>();
        final Set<String> fileIds = new LinkedHashSet<>();
        final StringBuilder digest = new StringBuilder(8192);
        int backfillCount;                      // records collected by the backfill pass this run
        ZonedDateTime backfillLatest;           // newest backfilled record seen this run (next watermark)

        void trackBackfill(ZonedDateTime time) {
            if (time == null) return;
            backfillCount++;
            if (backfillLatest == null || time.isAfter(backfillLatest)) backfillLatest = time;
        }

        boolean canGrow() {
            return digest.length() < MAX_DIGEST_CHARS;
        }

        boolean contains(String targetType, String targetId) {
            return switch (targetType) {
                case "session" -> sessionIds.contains(targetId);
                case "run" -> runIds.contains(targetId);
                case "workflow_run" -> workflowRunIds.contains(targetId);
                case "file" -> fileIds.contains(targetId);
                default -> false;
            };
        }
    }
}
