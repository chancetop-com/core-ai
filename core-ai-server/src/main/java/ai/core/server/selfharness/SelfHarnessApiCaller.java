package ai.core.server.selfharness;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct-method-call dispatcher for self-harness tools.
 * Mirrors {@link ai.core.mcp.server.apiserver.DynamicApiCaller} but calls
 * service-layer methods instead of making HTTP requests.
 *
 * @author stephen
 */
public class SelfHarnessApiCaller {
    private static final Logger LOGGER = LoggerFactory.getLogger(SelfHarnessApiCaller.class);
    static final int SEARCH_DEFAULT_LIMIT = 20;
    static final int SEARCH_MAX_LIMIT = 50;

    @Inject
    SelfHarnessDispatcher dispatcher;
    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    ai.core.server.apiuser.PermissionService permissionService;
    @Inject
    MongoCollection<ai.core.server.domain.User> userCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    MongoCollection<WorkflowRun> workflowRunCollection;
    @Inject
    MongoCollection<FileRecord> fileRecordCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;

    public String callApi(String name, String args, ExecutionContext context) {
        ActionLogContext.put("self-harness-call", name);
        LOGGER.info("self-harness call, name={}, args={}", name, args);
        try {
            var result = dispatcher.dispatch(name, args, context);
            return JSON.toJSON(result);
        } catch (Exception e) {
            LOGGER.error("self-harness call failed, name={}, args={}", name, args, e);
            return JSON.toJSON(Map.of("error", e.getMessage()));
        }
    }

    // ---- caller-scoped project material queries (used by the builtin project agent; scoped to the run's caller) ----

    public Map<String, Object> searchSessions(String projectId, List<String> agentIds, String since, String subjectId, Boolean attributed, ExecutionContext context) {
        var scope = requireProject(projectId, context);
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in("agent_id", agentIds != null && !agentIds.isEmpty() ? agentIds : scope.agentIds));
        if (since != null) filters.add(Filters.gt("last_message_at", ZonedDateTime.parse(since)));
        applyAttributionFilter(filters, projectId, subjectId, attributed, "session", "_id");
        filters.add(Filters.or(Filters.exists("deleted_at", false), Filters.eq("deleted_at", null)));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("last_message_at");
        query.limit = SEARCH_DEFAULT_LIMIT;
        return Map.of("sessions", chatSessionCollection.find(query).stream().map(session -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", session.id);
            row.put("title", session.title);
            row.put("agent_id", session.agentId);
            row.put("user_id", session.userId);
            row.put("last_message_at", session.lastMessageAt != null ? session.lastMessageAt.toString() : null);
            row.put("message_count", session.messageCount);
            return row;
        }).toList());
    }

    public Map<String, Object> searchRuns(String projectId, List<String> agentIds, String since, String subjectId, Boolean attributed, ExecutionContext context) {
        var scope = requireProject(projectId, context);
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in("agent_id", agentIds != null && !agentIds.isEmpty() ? agentIds : scope.agentIds));
        if (since != null) filters.add(Filters.gt("started_at", ZonedDateTime.parse(since)));
        applyAttributionFilter(filters, projectId, subjectId, attributed, "run", "_id");
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("started_at");
        query.limit = SEARCH_DEFAULT_LIMIT;
        return Map.of("runs", agentRunCollection.find(query).stream().map(run -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", run.id);
            row.put("agent_id", run.agentId);
            row.put("user_id", run.userId);
            row.put("status", run.status != null ? run.status.name() : null);
            row.put("input", truncate(run.input, 500));
            row.put("output", truncate(run.output, 500));
            row.put("started_at", run.startedAt != null ? run.startedAt.toString() : null);
            return row;
        }).toList());
    }

    public Map<String, Object> searchWorkflowRuns(String projectId, List<String> workflowIds, String since, String subjectId, Boolean attributed, ExecutionContext context) {
        var scope = requireProject(projectId, context);
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in("workflow_id", workflowIds != null && !workflowIds.isEmpty() ? workflowIds : scope.workflowIds));
        if (since != null) filters.add(Filters.gt("started_at", ZonedDateTime.parse(since)));
        applyAttributionFilter(filters, projectId, subjectId, attributed, "workflow_run", "_id");
        filters.add(Filters.or(Filters.exists("preview", false), Filters.eq("preview", Boolean.FALSE)));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("started_at");
        query.limit = SEARCH_DEFAULT_LIMIT;
        return Map.of("workflow_runs", workflowRunCollection.find(query).stream().map(run -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", run.id);
            row.put("workflow_id", run.workflowId);
            row.put("user_id", run.userId);
            row.put("status", run.status != null ? run.status.name() : null);
            row.put("input", truncate(run.input, 500));
            row.put("started_at", run.startedAt != null ? run.startedAt.toString() : null);
            return row;
        }).toList());
    }

    public Map<String, Object> listFiles(String since, String contentType, String keyword, Integer limit, ExecutionContext context) {
        var userId = requireUser(context);
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("user_id", userId));
        if (since != null) filters.add(Filters.gt("created_at", ZonedDateTime.parse(since)));
        if (contentType != null && !contentType.isBlank()) filters.add(Filters.eq("content_type", contentType));
        if (keyword != null && !keyword.isBlank()) {
            filters.add(Filters.regex("file_name", java.util.regex.Pattern.quote(keyword), "i"));
        }
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("created_at");
        query.limit = clampLimit(limit);
        return Map.of("files", fileRecordCollection.find(query).stream().map(file -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", file.id);
            row.put("file_name", file.fileName);
            row.put("content_type", file.contentType);
            row.put("size", file.size);
            row.put("created_at", file.createdAt != null ? file.createdAt.toString() : null);
            return row;
        }).toList());
    }

    public Map<String, Object> getFileContent(String fileId, ExecutionContext context) {
        var userId = requireUser(context);
        var file = fileRecordCollection.get(fileId)
            .orElseThrow(() -> new IllegalArgumentException("file not found, id=" + fileId));
        if (!userId.equals(file.userId)) throw new IllegalArgumentException("file does not belong to the current user");
        var row = new LinkedHashMap<String, Object>();
        row.put("id", file.id);
        row.put("file_name", file.fileName);
        row.put("content_type", file.contentType);
        row.put("text", file.data != null ? htmlToText(file.data) : "(content stored in object storage, not readable here)");
        return row;
    }

    private ProjectScope requireProject(String projectId, ExecutionContext context) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("project_id is required");
        }
        var userId = requireUser(context);
        var project = projectCollection.get(projectId)
            .orElseThrow(() -> new IllegalArgumentException("project not found, id=" + projectId));
        // a project is a shared business container: read access = owner/admin or project.view
        if (!ai.core.server.project.ProjectAccess.canView(project, userId, permissionService, userCollection)) {
            throw new IllegalArgumentException("project is not accessible to the current user");
        }
        var agentIds = new ArrayList<String>();
        var workflowIds = new ArrayList<String>();
        if (project.members != null) {
            for (var member : project.members) {
                if ("agent".equals(member.type)) {
                    agentIds.add(member.id);
                } else if ("workflow".equals(member.type)) {
                    workflowIds.add(member.id);
                }
            }
        }
        return new ProjectScope(agentIds, workflowIds);
    }

    private String requireUser(ExecutionContext context) {
        var caller = context != null ? context.getCaller() : null;
        var userId = caller != null ? caller.userId() : null;
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("no caller user context");
        return userId;
    }

    private void applyAttributionFilter(List<Bson> filters, String projectId, String subjectId, Boolean attributed, String targetType, String targetField) {
        if (subjectId != null) {
            var targets = attributionTargets(targetType, subjectId);
            filters.add(targets.isEmpty() ? Filters.eq(targetField, "__none__") : Filters.in(targetField, targets));
            return;
        }
        if (attributed != null) {
            var subjectIds = subjectIdsOf(projectId);
            var targets = attributionTargets(targetType, subjectIds.toArray(String[]::new));
            if (Boolean.TRUE.equals(attributed)) {
                filters.add(targets.isEmpty() ? Filters.eq(targetField, "__none__") : Filters.in(targetField, targets));
            } else {
                filters.add(targets.isEmpty() ? Filters.exists(targetField) : Filters.nin(targetField, targets));
            }
        }
    }

    private List<String> subjectIdsOf(String projectId) {
        var query = new Query();
        query.filter = Filters.eq("project_id", projectId);
        return subjectCollection.find(query).stream().map(s -> s.id).toList();
    }

    private List<String> attributionTargets(String targetType, String... subjectIds) {
        if (subjectIds.length == 0) return List.of();
        var query = new Query();
        query.filter = Filters.and(Filters.in("subject_id", List.of(subjectIds)), Filters.eq("target_type", targetType));
        return attributionCollection.find(query).stream().map(a -> a.targetId).toList();
    }

    private int clampLimit(Integer limit) {
        if (limit == null) return SEARCH_DEFAULT_LIMIT;
        return Math.min(Math.max(limit, 1), SEARCH_MAX_LIMIT);
    }

    private String truncate(String value, int maxChars) {
        if (value == null) return null;
        return value.length() > maxChars ? value.substring(0, maxChars) + "...(truncated)" : value;
    }

    private String htmlToText(String base64Html) {
        try {
            var decoded = java.util.Base64.getDecoder().decode(base64Html);
            return new String(decoded, StandardCharsets.UTF_8)
                .replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to decode file content for self-harness", e);
            return "";
        }
    }

    private record ProjectScope(List<String> agentIds, List<String> workflowIds) {
    }
}
