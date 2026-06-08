package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowMode;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
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
    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    public WorkflowDefinition create(String name, String mode, String graph, String userId) {
        var now = ZonedDateTime.now();
        var definition = new WorkflowDefinition();
        definition.id = UUID.randomUUID().toString();
        definition.userId = userId;
        definition.name = name;
        definition.mode = "CHATFLOW".equals(mode) ? WorkflowMode.CHATFLOW : WorkflowMode.WORKFLOW;
        definition.draftGraph = graph;
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

    public List<WorkflowDefinition> list(String userId) {
        return definitionCollection.find(Filters.eq("user_id", userId));
    }

    public WorkflowDefinition update(String id, String name, String graph, String userId) {
        var definition = get(id, userId);
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
}
