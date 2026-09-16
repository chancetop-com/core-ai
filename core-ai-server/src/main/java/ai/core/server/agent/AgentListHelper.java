package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.User;
import ai.core.server.skill.SkillService;
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
    private static final String FAVORITE_AGENT_IDS_FIELD = "favorite_agent_ids";

    static void prioritizeAssistant(List<AgentDefinition> agents, String agentId) {
        if (agentId == null) return;
        for (int i = 0; i < agents.size(); i++) {
            if (agentId.equals(agents.get(i).id)) {
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

    // the fork pins the caller's own assistant, the star toggle pins anything else
    static void addFavorite(MongoCollection<User> userCollection, String userId, String agentId) {
        initializeFavoritesIfNull(userCollection, userId);
        userCollection.update(Filters.eq("_id", userId), Updates.addToSet(FAVORITE_AGENT_IDS_FIELD, agentId));
    }

    // core-ng writes null fields explicitly on entity replace, so existing user documents may carry
    // favorite_agent_ids=null; $addToSet/$pull reject non-array fields, so normalize null to [] first.
    private static void initializeFavoritesIfNull(MongoCollection<User> userCollection, String userId) {
        var filter = Filters.and(Filters.eq("_id", userId),
                Filters.or(Filters.exists(FAVORITE_AGENT_IDS_FIELD, false), Filters.type(FAVORITE_AGENT_IDS_FIELD, "null")));
        userCollection.update(filter, Updates.set(FAVORITE_AGENT_IDS_FIELD, List.of()));
    }

    static Map<String, String> resolveSkillNames(SkillService skillService, List<AgentDefinition> entities) {
        var skillIds = new HashSet<String>();
        for (var entity : entities) {
            if (entity.skillIds != null) skillIds.addAll(entity.skillIds);
        }
        if (skillIds.isEmpty()) return Map.of();
        try {
            return skillService.batchResolve(skillIds);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private final MongoCollection<AgentDefinition> agentDefinitionCollection;
    private final MongoCollection<User> userCollection;

    AgentListHelper(MongoCollection<AgentDefinition> agentDefinitionCollection, MongoCollection<User> userCollection) {
        this.agentDefinitionCollection = agentDefinitionCollection;
        this.userCollection = userCollection;
    }

    AgentDefinition findDefaultAssistant(Bson filter) {
        return agentDefinitionCollection.findOne(AgentQueryHelper.combineFilters(filter,
            Filters.eq("_id", PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID))).orElse(null);
    }

    List<AgentDefinition> listWithAssistantFirst(Bson filter, String sortField, Integer skip, Integer limit, AgentDefinition assistant, Bson projection) {
        if (limit == null) {
            var agents = findAgents(filter, sortField, null, null, projection);
            prioritizeAssistant(agents, assistant.id);
            return agents;
        }
        if (skip != null && skip > 0) {
            return findAgents(excludeAssistant(filter, assistant.id), sortField, skip - 1, limit, projection);
        }
        var agents = limit > 1 ? findAgents(excludeAssistant(filter, assistant.id), sortField, 0, limit - 1, projection) : new ArrayList<AgentDefinition>();
        agents.add(0, assistant);
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
    List<AgentDefinition> searchAgents(Bson filter, String keyword, String sortField, String priorityAgentId) {
        var lower = keyword.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<AgentDefinition>();
        for (var agent : findAgents(filter, sortField, null, null, null)) {
            if (contains(agent.name, lower) || contains(agent.description, lower)) matches.add(agent);
        }
        prioritizeAssistant(matches, priorityAgentId);
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
        addFavorite(userCollection, userId, agentId);
    }

    void unfavorite(String agentId, String userId) {
        initializeFavoritesIfNull(userCollection, userId);
        userCollection.update(Filters.eq("_id", userId), Updates.pull(FAVORITE_AGENT_IDS_FIELD, agentId));
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

    Map<String, String> resolveSubAgentNames(List<AgentDefinition> entities) {
        var agentIds = new HashSet<String>();
        for (var entity : entities) {
            if (entity.subAgentIds != null) agentIds.addAll(entity.subAgentIds);
        }
        if (agentIds.isEmpty()) return Map.of();
        var map = new HashMap<String, String>();
        for (var agent : agentDefinitionCollection.find(new org.bson.Document("_id", new org.bson.Document("$in", new ArrayList<>(agentIds))))) {
            map.put(agent.id, agent.name);
        }
        return map;
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

    private Bson excludeAssistant(Bson filter, String assistantId) {
        return AgentQueryHelper.combineFilters(filter, Filters.ne("_id", assistantId));
    }
}
