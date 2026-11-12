package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class MongoAgentDefinitionStore implements AgentDefinitionStore {
    public static MongoAgentDefinitionStore of(MongoCollection<AgentDefinition> collection) {
        var store = new MongoAgentDefinitionStore();
        store.agentDefinitionCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Override
    public Optional<AgentDefinition> get(String id) {
        return agentDefinitionCollection.get(id);
    }

    @Override
    public List<AgentDefinition> find(AgentDefinitionQuery request) {
        var query = new Query();
        query.filter = filter(request);
        query.sort = "name_key".equals(request.sortField)
            ? Sorts.orderBy(Sorts.ascending("name_key"), Sorts.ascending("_id"))
            : request.ascending ? Sorts.ascending(request.sortField) : Sorts.descending(request.sortField);
        query.skip = request.offset;
        query.limit = request.limit;
        if (request.excludeSystemPrompt) query.projection = com.mongodb.client.model.Projections.exclude("system_prompt");
        if (request.summaryOnly) query.projection = com.mongodb.client.model.Projections.include("_id", "name");
        return agentDefinitionCollection.find(query);
    }

    @Override
    public long count(AgentDefinitionQuery request) {
        return agentDefinitionCollection.count(filter(request));
    }

    @Override
    public void insert(AgentDefinition definition) {
        agentDefinitionCollection.insert(definition);
    }

    @Override
    public void replace(AgentDefinition definition) {
        agentDefinitionCollection.replace(definition);
    }

    @Override
    public void upsert(AgentDefinition definition) {
        if (get(definition.id).isPresent()) {
            agentDefinitionCollection.replace(definition);
        } else {
            agentDefinitionCollection.insert(definition);
        }
    }

    @Override
    public void delete(String id) {
        agentDefinitionCollection.delete(id);
    }

    @Override
    public boolean insertIfAbsent(AgentDefinition definition) {
        try {
            agentDefinitionCollection.insert(definition);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    private Bson scopeFilter(AgentDefinitionQuery request) {
        return switch (request.scope) {
            case DIRECT -> Filters.eq("user_id", request.userId);
            case OWNED_OR_SYSTEM -> Filters.or(
                Filters.eq("user_id", request.userId), Filters.eq("system_default", Boolean.TRUE));
            case OWNED_NO_SYSTEM, WORKFLOW_MINE -> Filters.and(
                Filters.eq("user_id", request.userId), Filters.ne("system_default", Boolean.TRUE));
            case SHARED -> Filters.and(
                Filters.ne("user_id", request.userId),
                Filters.ne("system_default", Boolean.TRUE),
                Filters.eq("forked_from", null));
            case VISIBLE -> Filters.or(
                Filters.eq("user_id", request.userId),
                Filters.eq("system_default", Boolean.TRUE),
                Filters.and(Filters.ne("user_id", request.userId), Filters.ne("system_default", Boolean.TRUE),
                    Filters.eq("forked_from", null)));
            case WORKFLOW_SHARED -> Filters.and(
                Filters.eq("status", "PUBLISHED"),
                Filters.exists("published_config", true),
                Filters.ne("published_config", null),
                Filters.eq("forked_from", null),
                Filters.or(Filters.eq("system_default", Boolean.TRUE), Filters.ne("user_id", request.userId)));
            case ALL -> null;
        };
    }

    private Bson filter(AgentDefinitionQuery request) {
        var filters = new ArrayList<Bson>();
        var scope = scopeFilter(request);
        if (scope != null) filters.add(scope);
        if (request.forkVisibilityUserId != null) {
            filters.add(Filters.or(
                Filters.eq("forked_from", null), Filters.eq("user_id", request.forkVisibilityUserId)));
        }
        if (request.type != null) filters.add(Filters.eq("type", request.type));
        if (request.status != null) filters.add(Filters.eq("status", request.status));
        if (request.name != null) filters.add(Filters.eq("name", request.name));
        if (request.nameKey != null) filters.add(Filters.eq("name_key", request.nameKey));
        if (request.nameKeyPrefix != null) {
            filters.add(Filters.regex("name_key", Pattern.compile("^" + Pattern.quote(request.nameKeyPrefix))));
        }
        if (request.systemDefault != null) filters.add(Filters.eq("system_default", request.systemDefault));
        if (request.forkedFromIsNull != null) {
            filters.add(request.forkedFromIsNull ? Filters.eq("forked_from", null) : Filters.ne("forked_from", null));
        }
        if (request.hasPublishedConfig != null) {
            filters.add(request.hasPublishedConfig
                ? Filters.and(Filters.exists("published_config", true), Filters.ne("published_config", null))
                : Filters.or(Filters.exists("published_config", false), Filters.eq("published_config", null)));
        }
        if (request.ids != null) {
            filters.add(request.ids.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("_id", request.ids));
        }
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) {
            filters.add(Filters.nin("_id", request.excludeIds));
        }
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }
}
