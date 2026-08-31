package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * List query helpers for {@link AgentDefinitionService} to keep the service file under the length limit.
 *
 * @author stephen
 */
final class AgentListHelper {
    static final String DEFAULT_ASSISTANT_AGENT_ID = "default-assistant";
    private static final String FAVORITE_AGENT_IDS_FIELD = "favorite_agent_ids";

    static void prioritizeDefaultAssistant(List<AgentDefinition> agents) {
        for (int i = 0; i < agents.size(); i++) {
            if (DEFAULT_ASSISTANT_AGENT_ID.equals(agents.get(i).id)) {
                agents.add(0, agents.remove(i));
                return;
            }
        }
    }

    static AgentDefinitionView toSummaryView(AgentDefinition entity) {
        var view = new AgentDefinitionView();
        view.id = entity.id;
        view.name = entity.name;
        return view;
    }

    static Set<String> favoriteAgentIds(MongoCollection<User> userCollection, String userId) {
        return userCollection.get(userId)
                .map(user -> user.favoriteAgentIds)
                .orElse(List.of())
                .stream()
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean contains(String text, String lowerKeyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private final MongoCollection<AgentDefinition> agentDefinitionCollection;
    private final MongoCollection<User> userCollection;

    AgentListHelper(MongoCollection<AgentDefinition> agentDefinitionCollection, MongoCollection<User> userCollection) {
        this.agentDefinitionCollection = agentDefinitionCollection;
        this.userCollection = userCollection;
    }

    AgentDefinition findDefaultAssistant(Bson filter) {
        return agentDefinitionCollection.findOne(AgentQueryHelper.combineFilters(filter, Filters.eq("_id", DEFAULT_ASSISTANT_AGENT_ID))).orElse(null);
    }

    List<AgentDefinition> listWithDefaultAssistantFirst(Bson filter, String sortField, Integer skip, Integer limit, AgentDefinition defaultAssistant, Bson projection) {
        if (limit == null) {
            var agents = findAgents(filter, sortField, null, null, projection);
            prioritizeDefaultAssistant(agents);
            return agents;
        }
        if (skip != null && skip > 0) {
            return findAgents(excludeDefaultAssistant(filter), sortField, skip - 1, limit, projection);
        }
        var agents = limit > 1 ? findAgents(excludeDefaultAssistant(filter), sortField, 0, limit - 1, projection) : new ArrayList<AgentDefinition>();
        agents.add(0, defaultAssistant);
        return agents;
    }

    List<AgentDefinition> findAgents(Bson filter, String sortField, Integer skip, Integer limit, Bson projection) {
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending(sortField);
        if (skip != null) query.skip = skip;
        if (limit != null) query.limit = limit;
        if (projection != null) query.projection = projection;
        return agentDefinitionCollection.find(query);
    }

    // Mongo cannot use an index for an unanchored case-insensitive regex, so keyword search is rejected by
    // notablescan. Fetch the index-backed access matches and filter in Java instead, like TraceService does.
    List<AgentDefinition> searchAgents(Bson filter, String keyword, String sortField) {
        var lower = keyword.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<AgentDefinition>();
        for (var agent : findAgents(filter, sortField, null, null, null)) {
            if (contains(agent.name, lower) || contains(agent.description, lower)) matches.add(agent);
        }
        prioritizeDefaultAssistant(matches);
        return matches;
    }

    void markFavorites(ListAgentsResponse response, String userId) {
        var favoriteIds = favoriteAgentIds(userCollection, userId);
        if (favoriteIds.isEmpty()) return;
        for (var agent : response.agents) {
            if (favoriteIds.contains(agent.id)) agent.favorite = Boolean.TRUE;
        }
    }

    void favorite(String agentId, String userId) {
        agentDefinitionCollection.get(agentId)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + agentId));
        initializeFavoritesIfNull(userId);
        userCollection.update(Filters.eq("_id", userId), Updates.addToSet(FAVORITE_AGENT_IDS_FIELD, agentId));
    }

    void unfavorite(String agentId, String userId) {
        initializeFavoritesIfNull(userId);
        userCollection.update(Filters.eq("_id", userId), Updates.pull(FAVORITE_AGENT_IDS_FIELD, agentId));
    }

    // core-ng writes null fields explicitly on entity replace, so existing user documents may carry
    // favorite_agent_ids=null; $addToSet/$pull reject non-array fields, so normalize null to [] first.
    private void initializeFavoritesIfNull(String userId) {
        var filter = Filters.and(Filters.eq("_id", userId),
                Filters.or(Filters.exists(FAVORITE_AGENT_IDS_FIELD, false), Filters.type(FAVORITE_AGENT_IDS_FIELD, "null")));
        userCollection.update(filter, Updates.set(FAVORITE_AGENT_IDS_FIELD, List.of()));
    }

    ListAgentsResponse listFavorites(String userId) {
        var favoriteIds = userCollection.get(userId)
                .map(user -> user.favoriteAgentIds)
                .orElse(List.of());
        var ordered = new ArrayList<AgentDefinition>();
        if (!favoriteIds.isEmpty()) {
            var byId = new HashMap<String, AgentDefinition>();
            for (var agent : agentDefinitionCollection.find(Filters.in("_id", favoriteIds))) {
                byId.put(agent.id, agent);
            }
            for (int i = favoriteIds.size() - 1; i >= 0; i--) {
                var agent = byId.get(favoriteIds.get(i));
                if (agent != null) ordered.add(agent);
            }
        }
        var userNameMap = resolveUserNames(ordered);
        var views = ordered.stream().map(agent -> {
            var view = AgentViewHelper.buildView(agent, Map.of(), Map.of());
            view.createdBy = userNameMap.getOrDefault(agent.userId, agent.userId);
            view.favorite = Boolean.TRUE;
            return view;
        }).toList();
        var response = new ListAgentsResponse();
        response.agents = views;
        response.total = (long) views.size();
        return response;
    }

    Map<String, String> resolveUserNames(List<AgentDefinition> entities) {
        var userIds = new HashSet<String>();
        for (var entity : entities) {
            if (entity.userId != null) userIds.add(entity.userId);
            if (entity.updatedBy != null) userIds.add(entity.updatedBy);
        }
        if (userIds.isEmpty()) return Map.of();
        var map = new HashMap<String, String>();
        for (var u : userCollection.find(new org.bson.Document("_id", new org.bson.Document("$in", new ArrayList<>(userIds))))) {
            map.put(u.id, u.name);
        }
        return map;
    }

    private Bson excludeDefaultAssistant(Bson filter) {
        return AgentQueryHelper.combineFilters(filter, Filters.ne("_id", DEFAULT_ASSISTANT_AGENT_ID));
    }
}
