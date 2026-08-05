package ai.core.server.workflow;

import ai.core.api.server.workflow.ListWorkflowAgentOptionsRequest;
import ai.core.api.server.workflow.ListWorkflowAgentOptionsResponse;
import ai.core.api.server.workflow.WorkflowAgentOptionView;
import ai.core.server.agent.AgentNameKey;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.bson.conversions.Bson;

import java.util.regex.Pattern;

/**
 * @author Xander
 */
public class WorkflowAgentOptionService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    static Bson filter(String scope, DefinitionType type, String query, String userId) {
        Bson scopeFilter = switch (scope) {
            case "mine" -> Filters.and(
                Filters.eq("user_id", userId),
                Filters.ne("system_default", Boolean.TRUE),
                Filters.eq("type", type));
            case "shared" -> Filters.and(
                Filters.eq("status", AgentStatus.PUBLISHED),
                Filters.exists("published_config", true),
                Filters.ne("published_config", null),
                Filters.eq("type", type),
                Filters.or(Filters.eq("system_default", Boolean.TRUE), Filters.ne("user_id", userId)));
            default -> throw new BadRequestException("invalid scope: " + scope);
        };
        var prefix = AgentNameKey.normalize(query);
        if (prefix.isEmpty()) return scopeFilter;
        return Filters.and(scopeFilter,
            Filters.regex("name_key", Pattern.compile("^" + Pattern.quote(prefix))));
    }

    static WorkflowAgentOptionView toView(AgentDefinition agent, String userId) {
        var view = new WorkflowAgentOptionView();
        view.id = agent.id;
        view.name = agent.name;
        view.type = agent.type.name();
        view.status = agent.status.name();
        view.ownership = Boolean.TRUE.equals(agent.systemDefault)
            ? "SYSTEM"
            : userId.equals(agent.userId) ? "MINE" : "SHARED";
        view.updatedAt = agent.updatedAt;
        return view;
    }

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    public ListWorkflowAgentOptionsResponse list(String userId, ListWorkflowAgentOptionsRequest request) {
        if (request == null) throw new BadRequestException("invalid scope: null");
        var scope = scope(request.scope);
        var type = type(request.type);
        int page = request.page == null ? 1 : Math.max(1, request.page);
        int limit = request.limit == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, request.limit));
        var filter = filter(scope, type, request.query, userId);
        long offset = ((long) page - 1) * limit;
        if (offset > Integer.MAX_VALUE) {
            throw new BadRequestException("page offset exceeds supported range");
        }

        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.ascending("name_key", "_id");
        query.skip = (int) offset;
        query.limit = limit;

        var response = new ListWorkflowAgentOptionsResponse();
        response.items = agentDefinitionCollection.find(query).stream()
            .filter(agent -> canList(agent, scope, type, userId))
            .map(agent -> toView(agent, userId))
            .toList();
        response.total = agentDefinitionCollection.count(filter);
        response.page = page;
        response.limit = limit;
        response.selected = selected(request.selectedId, type, userId);
        return response;
    }

    private boolean canList(AgentDefinition agent, String scope, DefinitionType type, String userId) {
        if (agent == null || agent.type != type || userId == null) return false;
        return switch (scope) {
            case "mine" -> WorkflowAgentAccessPolicy.isOwnedEditable(agent, userId);
            case "shared" -> WorkflowAgentAccessPolicy.hasUsablePublishedConfig(agent)
                             && (Boolean.TRUE.equals(agent.systemDefault) || !userId.equals(agent.userId));
            default -> false;
        };
    }

    private WorkflowAgentOptionView selected(String selectedId, DefinitionType type, String userId) {
        if (selectedId == null || selectedId.isBlank()) return null;
        return agentDefinitionCollection.get(selectedId)
            .filter(agent -> agent.type == type)
            .filter(agent -> WorkflowAgentAccessPolicy.canReference(agent, userId))
            .map(agent -> toView(agent, userId))
            .orElse(null);
    }

    private String scope(String scope) {
        if ("mine".equals(scope) || "shared".equals(scope)) return scope;
        throw new BadRequestException("invalid scope: " + scope);
    }

    private DefinitionType type(String type) {
        if (type == null) throw new BadRequestException("invalid type: null");
        try {
            return DefinitionType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid type: " + type, "BAD_REQUEST", e);
        }
    }
}
