package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.user.UserStore;
import ai.core.server.skill.SkillService;

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

    static Set<String> favoriteAgentIds(UserStore userStore, String userId) {
        return userStore.get(userId)
                .map(user -> user.favoriteAgentIds)
                .orElse(List.of())
                .stream()
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean contains(String text, String lowerKeyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    // the fork pins the caller's own assistant, the star toggle pins anything else
    static void addFavorite(UserStore userStore, String userId, String agentId) {
        userStore.update(userId, user -> {
            var favorites = user.favoriteAgentIds == null ? new ArrayList<String>() : new ArrayList<>(user.favoriteAgentIds);
            if (!favorites.contains(agentId)) favorites.add(agentId);
            user.favoriteAgentIds = favorites;
        });
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

    private final AgentDefinitionStore agentDefinitionStore;
    private final UserStore userStore;

    AgentListHelper(AgentDefinitionStore agentDefinitionStore, UserStore userStore) {
        this.agentDefinitionStore = agentDefinitionStore;
        this.userStore = userStore;
    }

    AgentDefinition findDefaultAssistant(AgentDefinitionQuery query) {
        return agentDefinitionStore.findOne(query).orElse(null);
    }

    List<AgentDefinition> listWithAssistantFirst(AgentDefinitionQuery query, Integer skip, Integer limit, AgentDefinition assistant) {
        if (limit == null) {
            // the pg store returns an immutable list, and prioritizeAssistant reorders in place
            var agents = new ArrayList<>(findAgents(query, null, null));
            prioritizeAssistant(agents, assistant.id);
            return agents;
        }
        if (skip != null && skip > 0) {
            excludeAssistant(query, assistant.id);
            return findAgents(query, skip - 1, limit);
        }
        List<AgentDefinition> agents;
        if (limit > 1) {
            excludeAssistant(query, assistant.id);
            agents = new ArrayList<>(findAgents(query, 0, limit - 1));
        } else {
            agents = new ArrayList<>();
        }
        agents.add(0, assistant);
        return agents;
    }

    private void excludeAssistant(AgentDefinitionQuery query, String assistantId) {
        var excludeIds = query.excludeIds == null ? new ArrayList<String>() : new ArrayList<>(query.excludeIds);
        excludeIds.add(assistantId);
        query.excludeIds = excludeIds;
    }

    List<AgentDefinition> findAgents(AgentDefinitionQuery query, Integer skip, Integer limit) {
        query.offset = skip != null ? skip : 0;
        query.limit = limit != null ? limit : Integer.MAX_VALUE;
        return agentDefinitionStore.find(query);
    }

    // Mongo cannot use an index for an unanchored case-insensitive regex, so keyword search is rejected by
    // notablescan. Fetch the index-backed access matches and filter in Java instead, like TraceService does.
    List<AgentDefinition> searchAgents(AgentDefinitionQuery query, String keyword, String sortField, String priorityAgentId) {
        var lower = keyword.toLowerCase(Locale.ROOT);
        query.sortField = sortField;
        query.limit = Integer.MAX_VALUE;
        var matches = new ArrayList<AgentDefinition>();
        for (var agent : agentDefinitionStore.find(query)) {
            if (contains(agent.name, lower) || contains(agent.description, lower)) matches.add(agent);
        }
        prioritizeAssistant(matches, priorityAgentId);
        return matches;
    }

    void markFavorites(ListAgentsResponse response, String userId) {
        var favoriteIds = favoriteAgentIds(userStore, userId);
        if (favoriteIds.isEmpty()) return;
        for (var agent : response.agents) {
            if (favoriteIds.contains(agent.id)) agent.favorite = Boolean.TRUE;
        }
    }

    void favorite(String agentId, String userId) {
        agentDefinitionStore.get(agentId)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + agentId));
        addFavorite(userStore, userId, agentId);
    }

    void unfavorite(String agentId, String userId) {
        userStore.update(userId, user -> {
            if (user.favoriteAgentIds == null) return;
            var favorites = new ArrayList<>(user.favoriteAgentIds);
            favorites.remove(agentId);
            user.favoriteAgentIds = favorites;
        });
    }

    ListAgentsResponse listFavorites(String userId) {
        var favoriteIds = userStore.get(userId)
                .map(user -> user.favoriteAgentIds)
                .orElse(List.of());
        var ordered = new ArrayList<AgentDefinition>();
        if (!favoriteIds.isEmpty()) {
            var byId = new HashMap<String, AgentDefinition>();
            var query = new AgentDefinitionQuery();
            query.ids = favoriteIds;
            query.forkVisibilityUserId = userId;
            query.limit = Integer.MAX_VALUE;
            for (var agent : agentDefinitionStore.find(query)) {
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
        var query = new AgentDefinitionQuery();
        query.ids = new ArrayList<>(agentIds);
        query.limit = agentIds.size();
        for (var agent : agentDefinitionStore.find(query)) {
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
        for (var u : userStore.listByIds(userIds)) {
            map.put(u.id, u.name);
        }
        return map;
    }

}
