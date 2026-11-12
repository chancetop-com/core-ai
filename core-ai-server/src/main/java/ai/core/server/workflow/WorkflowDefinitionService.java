package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowDefinitionStatus;
import ai.core.server.domain.WorkflowMode;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowVersionStatus;
import ai.core.server.domain.WorkflowVisibility;
import core.framework.inject.Inject;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for workflow drafts. Users only edit the draft; {@link WorkflowPublishService} freezes it on publish.
 *
 * @author Xander
 */
public class WorkflowDefinitionService {
    // A detached read-only projection of another user's published workflow: same identity/metadata, but the graph is
    // the frozen published one. Never inserted/replaced, so it can never overwrite the owner's real draft.
    private static WorkflowDefinition readOnlyCopy(WorkflowDefinition source, String publishedGraph) {
        var copy = new WorkflowDefinition();
        copy.id = source.id;
        copy.userId = source.userId;
        copy.name = source.name;
        copy.description = source.description;
        copy.mode = source.mode;
        copy.draftGraph = publishedGraph;
        copy.visibility = source.visibility;
        copy.status = source.status;
        copy.publishedVersionId = source.publishedVersionId;
        copy.publishedVersion = source.publishedVersion;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        return copy;
    }

    public static WorkflowDefinitionStatus statusOf(WorkflowDefinition definition) {
        return definition.status != null ? definition.status : WorkflowDefinitionStatus.ACTIVE;
    }

    public static WorkflowVisibility visibilityOf(WorkflowDefinition definition) {
        if (definition.visibility != null) {
            return definition.visibility;
        }
        return definition.publishedVersionId != null ? WorkflowVisibility.PUBLIC : WorkflowVisibility.PRIVATE;
    }

    public static boolean isPublicActive(WorkflowDefinition definition) {
        return definition.publishedVersionId != null
            && statusOf(definition) == WorkflowDefinitionStatus.ACTIVE
            && visibilityOf(definition) == WorkflowVisibility.PUBLIC;
    }

    @Inject
    WorkflowDefinitionStore definitionStore;

    @Inject
    WorkflowVersionStore versionStore;

    @Inject
    WorkflowRunStore runStore;

    public WorkflowDefinition create(String name, String mode, String graph, String userId) {
        var now = ZonedDateTime.now();
        var definition = new WorkflowDefinition();
        definition.id = UUID.randomUUID().toString();
        definition.userId = userId;
        definition.name = name;
        definition.mode = "CHATFLOW".equals(mode) ? WorkflowMode.CHATFLOW : WorkflowMode.WORKFLOW;
        definition.draftGraph = graph;
        definition.visibility = WorkflowVisibility.PRIVATE;
        definition.status = WorkflowDefinitionStatus.ACTIVE;
        definition.createdAt = now;
        definition.updatedAt = now;
        definitionStore.insert(definition);
        return definition;
    }

    public WorkflowDefinition get(String id, String userId) {
        var definition = definitionStore.get(id)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + id));
        if (!definition.userId.equals(userId)) {
            throw new ForbiddenException("workflow does not belong to the current user: " + id);
        }
        return definition;
    }

    // Read for VIEW/RUN (not edit): the owner gets the editable draft; any other user may open a PUBLISHED workflow
    // read-only, rendered from the frozen published graph (never the owner's unpublished draft). The non-owner gets a
    // DETACHED copy with the published graph, so the published graph can never be persisted back over the owner's draft.
    public WorkflowDefinition getReadable(String id, String userId) {
        var definition = definitionStore.get(id)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + id));
        if (definition.userId.equals(userId)) {
            return definition;
        }
        if (!isPublicActive(definition)) {
            throw new ForbiddenException("workflow does not belong to the current user: " + id);
        }
        var published = versionStore.get(definition.publishedVersionId)
            .orElseThrow(() -> new NotFoundException("published version not found: " + definition.publishedVersionId));
        return readOnlyCopy(definition, WorkflowGraphSanitizer.sanitize(published.graph));
    }

    public String getVersionGraph(String versionId, String userId) {
        WorkflowPublishedVersion version = versionStore.get(versionId)
            .orElseThrow(() -> new NotFoundException("workflow version not found: " + versionId));
        if (Boolean.TRUE.equals(version.preview)) {
            throw new ForbiddenException("preview workflow versions are not callable: " + versionId);
        }
        WorkflowDefinition definition = definitionStore.get(version.workflowId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + version.workflowId));
        if (version.status == WorkflowVersionStatus.DISABLED) {
            throw new ForbiddenException("workflow version is disabled: " + versionId);
        }
        if (!definition.userId.equals(userId) && !isPublicActive(definition)) {
            throw new ForbiddenException("workflow version is not readable: " + versionId);
        }
        return definition.userId.equals(userId) ? version.graph : WorkflowGraphSanitizer.sanitize(version.graph);
    }

    public List<WorkflowDefinition> list(String userId) {
        return list(userId, null);
    }

    // myWorkflows: null/true -> the caller's active workflows; false -> other users' active public workflows.
    public List<WorkflowDefinition> list(String userId, Boolean myWorkflows) {
        return list(userId, myWorkflows, null, null, null);
    }

    public List<WorkflowDefinition> list(String userId, Boolean myWorkflows, String keyword, Integer offset, Integer limit) {
        return definitionStore.listActive(userId, myWorkflows, keyword,
            offset != null ? Math.max(0, offset) : 0,
            limit != null ? Math.min(Math.max(limit, 1), 100) : 100);
    }

    public long listCount(String userId, Boolean myWorkflows, String keyword) {
        return definitionStore.countActive(userId, myWorkflows, keyword);
    }

    // The owner's archived workflows: delete() archives instead of hard-deleting once runs or published versions
    // exist, and this list is the only way back to those workflows (and to the run history attached to them).
    public List<WorkflowDefinition> listArchived(String userId, String keyword, Integer offset, Integer limit) {
        return definitionStore.listArchived(userId, keyword,
            offset != null ? Math.max(0, offset) : 0,
            limit != null ? Math.min(Math.max(limit, 1), 100) : 100);
    }

    public long listArchivedCount(String userId, String keyword) {
        return definitionStore.countArchived(userId, keyword);
    }

    // Other users' public workflows for the Explore page: optional case-insensitive substring match on name,
    // newest-updated first, paged. Null visibility/status are treated as legacy PUBLIC/ACTIVE when a published
    // pointer exists, so existing published workflows remain discoverable until they are touched.
    public List<WorkflowDefinition> explore(String userId, String keyword, int offset, int limit) {
        return definitionStore.explore(userId, keyword, Math.max(0, offset), Math.min(Math.max(limit, 1), 100));
    }

    public long exploreCount(String userId, String keyword) {
        return definitionStore.exploreCount(userId, keyword);
    }

    // Copy another user's published workflow into a fresh draft owned by the caller. Clones the frozen published
    // graph (not the owner's live draft), so the caller gets exactly what was published. The cloned graph still
    // references the original author's agents; the caller must swap in their own agents before they can republish.
    public WorkflowDefinition clone(String sourceId, String userId) {
        var source = definitionStore.get(sourceId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + sourceId));
        if (!isPublicActive(source)) {
            throw new ForbiddenException("workflow is not published and cannot be cloned: " + sourceId);
        }
        var published = versionStore.get(source.publishedVersionId)
            .orElseThrow(() -> new NotFoundException("published version not found: " + source.publishedVersionId));
        var now = ZonedDateTime.now();
        var definition = new WorkflowDefinition();
        definition.id = UUID.randomUUID().toString();
        definition.userId = userId;
        definition.name = source.name;
        definition.description = source.description;
        definition.mode = source.mode;
        definition.draftGraph = WorkflowGraphSanitizer.sanitize(published.graph);
        definition.createdAt = now;
        definition.updatedAt = now;
        definitionStore.insert(definition);
        return definition;
    }

    public void delete(String id, String userId, boolean admin) {
        var definition = definitionStore.get(id)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + id));
        boolean owner = definition.userId.equals(userId);
        if (!owner && !(admin && isPublicActive(definition))) {
            throw new ForbiddenException("workflow does not belong to the current user: " + id);
        }
        if (canHardDelete(definition)) {
            definitionStore.delete(id);
            return;
        }
        definition.status = WorkflowDefinitionStatus.ARCHIVED;
        definition.visibility = WorkflowVisibility.PRIVATE;
        definition.archivedAt = ZonedDateTime.now();
        definition.archivedBy = userId;
        definition.updatedAt = definition.archivedAt;
        definitionStore.replace(definition);
    }

    public void delete(String id, String userId) {
        delete(id, userId, false);
    }

    public WorkflowDefinition update(String id, String name, String graph, String userId) {
        var definition = get(id, userId);
        if (statusOf(definition) != WorkflowDefinitionStatus.ACTIVE) {
            throw new ConflictException("workflow is not editable (status=" + statusOf(definition) + "): " + id);
        }
        if (name != null) {
            definition.name = name;
        }
        if (graph != null) {
            definition.draftGraph = graph;
        }
        definition.updatedAt = ZonedDateTime.now();
        definitionStore.replace(definition);
        return definition;
    }

    private boolean canHardDelete(WorkflowDefinition definition) {
        if (definition.publishedVersionId != null) {
            return false;
        }
        if (versionStore.listAll(definition.id).stream().anyMatch(version -> !Boolean.TRUE.equals(version.preview))) {
            return false;
        }
        return runStore.listByWorkflow(definition.id, definition.userId, true).isEmpty();
    }
}
