package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.AgentHealthView;
import ai.core.server.agenthealth.AgentHealthService;
import ai.core.server.agenthealth.AgentReferenceTracker;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.skill.SkillService;

import java.util.List;
import java.util.Map;

/**
 * Attaches derived health to agent views and forwards save/delete events to the reference tracker.
 * Keeping it separate keeps AgentDefinitionService focused on CRUD, publish and permissions.
 *
 * @author core-ai
 */
final class AgentHealthViewAttachment {
    static List<AgentDefinitionView> fullViews(List<AgentDefinition> paged, String userId, AgentListHelper listHelper,
                                               SkillService skillService, AgentHealthService healthService) {
        var userNameMap = listHelper.resolveUserNames(paged);
        var subAgentNameMap = listHelper.resolveSubAgentNames(paged);
        var skillNameMap = AgentListHelper.resolveSkillNames(skillService, paged);
        var health = healthService == null ? Map.<String, AgentHealthView>of()
            : healthService.snapshot(paged, userId);
        return paged.stream().map(entity -> {
            var view = toView(entity, userNameMap, subAgentNameMap, skillNameMap);
            view.health = health.get(entity.id);
            return view;
        }).toList();
    }

    static AgentHealthView health(AgentDefinition entity, String userId, AgentHealthService healthService) {
        return healthService == null ? null : healthService.snapshot(entity, userId);
    }

    static void refresh(AgentDefinition entity, String userId, AgentReferenceTracker tracker) {
        if (tracker != null) tracker.onAgentSaved(entity, userId);
    }

    static void deleted(String agentId, AgentReferenceTracker tracker) {
        if (tracker != null) tracker.onAgentDeleted(agentId);
    }

    private static AgentDefinitionView toView(AgentDefinition entity, Map<String, String> userNameMap,
                                              Map<String, String> subAgentNameMap, Map<String, String> skillNameMap) {
        var view = AgentViewHelper.buildListView(entity, subAgentNameMap, skillNameMap);
        view.createdBy = userNameMap.getOrDefault(entity.userId, entity.userId);
        var updatedBy = AgentViewHelper.lastModifiedBy(entity);
        view.updatedBy = userNameMap.getOrDefault(updatedBy, updatedBy);
        return view;
    }

    private AgentHealthViewAttachment() {
    }
}
