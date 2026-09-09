package ai.core.server.project;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ChatSession;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic attribution of runs and artifacts, so a report's home does not depend on the attributor
 * LLM whenever the execution context already knows it:
 * <ul>
 *   <li>{@link #bindRun}: a run fired by a schedule bound to a project subject is attributed at creation.</li>
 *   <li>{@link #onArtifact}: a freshly submitted artifact inherits the (single) subject its session/run is
 *       attributed to, per project.</li>
 *   <li>{@link #cascade}: attributing a session/run (attributor or manual) pulls its existing artifacts along.</li>
 * </ul>
 * Ambiguity (a parent attributed to several subjects of the same project) is left alone: the file stays
 * unassigned until the attributor or a person decides. Every entry point swallows failures — attribution
 * must never break artifact delivery or run start.
 *
 * @author stephen
 */
public class ProjectArtifactBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectArtifactBinder.class);

    @Inject
    ProjectAttributionStore store;
    @Inject
    MongoCollection<AgentSchedule> scheduleCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    /** schedule → run: called right after the run record is inserted */
    public void bindRun(AgentRun run) {
        if (run == null || run.scheduleId == null || run.scheduleId.isBlank()) return;
        try {
            var binding = scheduleBinding(run.scheduleId);
            if (binding == null) return;
            store.attribute(binding.projectId, binding.subjectId, ProjectAttributionStore.TARGET_RUN, run.id, ProjectAttributionStore.SOURCE_SCHEDULE);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to bind run to schedule subject, runId={}, scheduleId={}", run.id, run.scheduleId, e);
        }
    }

    /** parent (session|run) → new artifact: inherit the parent's subject in every project where it is unambiguous */
    public void onArtifact(String parentType, String parentId, String fileId) {
        onArtifact(parentType, parentId, fileId, null, null);
    }

    /** same, with the file metadata the sink already holds (denormalized onto the attribution row for paging) */
    public void onArtifact(String parentType, String parentId, String fileId, String agentId, ZonedDateTime createdAt) {
        if (fileId == null || fileId.isBlank() || parentId == null) return;
        try {
            var subjectsByProject = subjectsByProject(parentType, parentId);
            if (subjectsByProject.isEmpty() && ProjectAttributionStore.TARGET_RUN.equals(parentType)) {
                // run created before the schedule was bound, or bindRun failed: resolve the schedule now
                var run = agentRunCollection.get(parentId).orElse(null);
                if (run != null && run.scheduleId != null) {
                    bindRun(run);
                    subjectsByProject = subjectsByProject(parentType, parentId);
                }
            }
            for (var entry : subjectsByProject.entrySet()) {
                if (entry.getValue().size() != 1) continue;   // ambiguous parent: leave the file unassigned
                store.attributeFile(entry.getKey(), entry.getValue().iterator().next(), fileId, ProjectAttributionStore.SOURCE_INHERITED, agentId, createdAt);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to inherit artifact attribution, parentType={}, parentId={}, fileId={}", parentType, parentId, fileId, e);
        }
    }

    /** attributed parent → its existing artifacts; returns the number of files newly attributed */
    public int cascade(String projectId, String subjectId, String parentType, String parentId) {
        try {
            var subjects = subjectsByProject(parentType, parentId).getOrDefault(projectId, Set.of());
            if (subjects.size() > 1) return 0;   // parent now spans several subjects of this project: do not guess
            int count = 0;
            var parent = parentArtifacts(parentType, parentId);
            var seen = new java.util.HashSet<String>();
            for (var artifact : parent.artifacts()) {
                if (artifact.fileId == null || artifact.fileId.isBlank() || !seen.add(artifact.fileId)) continue;
                var result = store.attributeFile(projectId, subjectId, artifact.fileId, ProjectAttributionStore.SOURCE_CASCADE, parent.agentId(), artifact.createdAt);
                if (result == ProjectAttributionStore.Result.INSERTED) count++;
            }
            return count;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to cascade attribution to artifacts, parentType={}, parentId={}", parentType, parentId, e);
            return 0;
        }
    }

    List<String> artifactFileIds(String parentType, String parentId) {
        return parentArtifacts(parentType, parentId).artifacts().stream().map(a -> a.fileId).filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    private ParentArtifacts parentArtifacts(String parentType, String parentId) {
        return switch (parentType) {
            case ProjectAttributionStore.TARGET_SESSION -> chatSessionCollection.get(parentId)
                .map(s -> new ParentArtifacts(s.agentId, s.artifacts != null ? s.artifacts : List.<AgentRunArtifact>of())).orElse(ParentArtifacts.EMPTY);
            case ProjectAttributionStore.TARGET_RUN -> agentRunCollection.get(parentId)
                .map(r -> new ParentArtifacts(r.agentId, r.artifacts != null ? r.artifacts : List.<AgentRunArtifact>of())).orElse(ParentArtifacts.EMPTY);
            default -> ParentArtifacts.EMPTY;
        };
    }

    private Map<String, Set<String>> subjectsByProject(String targetType, String targetId) {
        var result = new HashMap<String, Set<String>>();
        for (var row : store.byTarget(targetType, targetId)) {
            var projectId = row.projectId != null ? row.projectId : store.subject(row.subjectId).map(s -> s.projectId).orElse(null);
            if (projectId == null) continue;
            result.computeIfAbsent(projectId, k -> new LinkedHashSet<>()).add(row.subjectId);
        }
        return result;
    }

    private Binding scheduleBinding(String scheduleId) {
        var schedule = scheduleCollection.get(scheduleId).orElse(null);
        if (schedule == null || schedule.subjectId == null || schedule.subjectId.isBlank()) return null;
        var subject = store.subject(schedule.subjectId).orElse(null);
        if (subject == null) return null;
        if (schedule.projectId != null && !schedule.projectId.equals(subject.projectId)) return null;   // stale binding
        return new Binding(subject.projectId, subject.id);
    }

    private record Binding(String projectId, String subjectId) {
    }

    private record ParentArtifacts(String agentId, List<AgentRunArtifact> artifacts) {
        static final ParentArtifacts EMPTY = new ParentArtifacts(null, List.of());
    }
}
