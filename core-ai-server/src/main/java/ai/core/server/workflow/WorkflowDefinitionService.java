package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowDefinitionStatus;
import ai.core.server.domain.WorkflowMode;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVersionStatus;
import ai.core.server.domain.WorkflowVisibility;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CRUD for workflow drafts. Users only edit the draft; {@link WorkflowPublishService} freezes it on publish.
 *
 * @author Xander
 */
public class WorkflowDefinitionService {
    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Inject
    MongoCollection<WorkflowRun> runCollection;

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
        definitionCollection.insert(definition);
        return definition;
    }

    public WorkflowDefinition get(String id, String userId) {
        var definition = definitionCollection.get(id)
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
        var definition = definitionCollection.get(id)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + id));
        if (definition.userId.equals(userId)) {
            return definition;
        }
        if (!isPublicActive(definition)) {
            throw new ForbiddenException("workflow does not belong to the current user: " + id);
        }
        var published = versionCollection.get(definition.publishedVersionId)
            .orElseThrow(() -> new NotFoundException("published version not found: " + definition.publishedVersionId));
        return readOnlyCopy(definition, WorkflowGraphSanitizer.sanitize(published.graph));
    }

    public String getVersionGraph(String versionId, String userId) {
        WorkflowPublishedVersion version = versionCollection.get(versionId)
            .orElseThrow(() -> new NotFoundException("workflow version not found: " + versionId));
        if (Boolean.TRUE.equals(version.preview)) {
            throw new ForbiddenException("preview workflow versions are not callable: " + versionId);
        }
        WorkflowDefinition definition = definitionCollection.get(version.workflowId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + version.workflowId));
        if (version.status == WorkflowVersionStatus.DISABLED) {
            throw new ForbiddenException("workflow version is disabled: " + versionId);
        }
        if (!definition.userId.equals(userId) && !isPublicActive(definition)) {
            throw new ForbiddenException("workflow version is not readable: " + versionId);
        }
        return definition.userId.equals(userId) ? version.graph : WorkflowGraphSanitizer.sanitize(version.graph);
    }

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

    public List<WorkflowDefinition> list(String userId) {
        return list(userId, null);
    }

    // myWorkflows: null/true -> the caller's active workflows; false -> other users' active public workflows.
    public List<WorkflowDefinition> list(String userId, Boolean myWorkflows) {
        return list(userId, myWorkflows, null, null, null);
    }

    public List<WorkflowDefinition> list(String userId, Boolean myWorkflows, String keyword, Integer offset, Integer limit) {
        var query = new Query();
        query.filter = listFilter(userId, myWorkflows, keyword);
        query.sort = Sorts.descending("updated_at");
        if (offset != null || limit != null) {
            query.skip = Math.max(0, offset != null ? offset : 0);
            query.limit = Math.min(Math.max(limit != null ? limit : 20, 1), 100);
        }
        return definitionCollection.find(query);
    }

    public long listCount(String userId, Boolean myWorkflows, String keyword) {
        return definitionCollection.count(listFilter(userId, myWorkflows, keyword));
    }

    // Other users' public workflows for the Explore page: optional case-insensitive substring match on name,
    // newest-updated first, paged. Null visibility/status are treated as legacy PUBLIC/ACTIVE when a published
    // pointer exists, so existing published workflows remain discoverable until they are touched.
    public List<WorkflowDefinition> explore(String userId, String keyword, int offset, int limit) {
        var query = new Query();
        query.filter = exploreFilter(userId, keyword);
        query.sort = Sorts.descending("updated_at");
        query.skip = Math.max(0, offset);
        query.limit = Math.min(Math.max(limit, 1), 100);
        return definitionCollection.find(query);
    }

    public long exploreCount(String userId, String keyword) {
        return definitionCollection.count(exploreFilter(userId, keyword));
    }

    private static Bson exploreFilter(String userId, String keyword) {
        var conditions = baseListFilters(userId, false);
        if (keyword != null && !keyword.isBlank()) {
            conditions.add(Filters.regex("name", Pattern.quote(keyword), "i"));
        }
        return Filters.and(conditions);
    }

    private static Bson listFilter(String userId, Boolean myWorkflows, String keyword) {
        var conditions = baseListFilters(userId, myWorkflows);
        if (keyword != null && !keyword.isBlank()) {
            conditions.add(Filters.regex("name", Pattern.quote(keyword), "i"));
        }
        return Filters.and(conditions);
    }

    private static ArrayList<Bson> baseListFilters(String userId, Boolean myWorkflows) {
        var conditions = new ArrayList<Bson>();
        if (myWorkflows != null && !myWorkflows) {
            conditions.add(Filters.ne("user_id", userId));
            conditions.add(Filters.ne("published_version_id", null));
            conditions.add(Filters.or(Filters.eq("visibility", WorkflowVisibility.PUBLIC), Filters.eq("visibility", null)));
        } else {
            conditions.add(Filters.eq("user_id", userId));
        }
        conditions.add(Filters.ne("status", WorkflowDefinitionStatus.ARCHIVED));
        conditions.add(Filters.ne("status", WorkflowDefinitionStatus.DISABLED));
        return conditions;
    }

    // Copy another user's published workflow into a fresh draft owned by the caller. Clones the frozen published
    // graph (not the owner's live draft), so the caller gets exactly what was published. The cloned graph still
    // references the original author's agents; the caller must swap in their own agents before they can republish.
    public WorkflowDefinition clone(String sourceId, String userId) {
        var source = definitionCollection.get(sourceId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + sourceId));
        if (!isPublicActive(source)) {
            throw new ForbiddenException("workflow is not published and cannot be cloned: " + sourceId);
        }
        var published = versionCollection.get(source.publishedVersionId)
            .orElseThrow(() -> new NotFoundException("published version not found: " + source.publishedVersionId));
        var now = ZonedDateTime.now();
        var definition = new WorkflowDefinition();
        definition.id = UUID.randomUUID().toString();
        definition.userId = userId;
        definition.name = source.name;
        definition.description = source.description;
        definition.mode = source.mode;
        definition.draftGraph = published.graph;
        definition.createdAt = now;
        definition.updatedAt = now;
        definitionCollection.insert(definition);
        return definition;
    }

    public void delete(String id, String userId, boolean admin) {
        var definition = definitionCollection.get(id)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + id));
        boolean owner = definition.userId.equals(userId);
        if (!owner && !(admin && isPublicActive(definition))) {
            throw new ForbiddenException("workflow does not belong to the current user: " + id);
        }
        if (canHardDelete(definition)) {
            definitionCollection.delete(id);
            return;
        }
        definition.status = WorkflowDefinitionStatus.ARCHIVED;
        definition.visibility = WorkflowVisibility.PRIVATE;
        definition.archivedAt = ZonedDateTime.now();
        definition.archivedBy = userId;
        definition.updatedAt = definition.archivedAt;
        definitionCollection.replace(definition);
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
        definitionCollection.replace(definition);
        return definition;
    }

    private boolean canHardDelete(WorkflowDefinition definition) {
        if (definition.publishedVersionId != null) {
            return false;
        }
        if (!versionCollection.find(Filters.and(
            Filters.eq("workflow_id", definition.id),
            Filters.ne("preview", true))).isEmpty()) {
            return false;
        }
        return runCollection.find(Filters.eq("workflow_id", definition.id)).isEmpty();
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
}
