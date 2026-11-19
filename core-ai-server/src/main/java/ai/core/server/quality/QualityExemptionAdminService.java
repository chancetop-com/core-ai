package ai.core.server.quality;

import ai.core.api.server.quality.AddQualityExemptionRequest;
import ai.core.api.server.quality.QualityExemptionGroupView;
import ai.core.api.server.quality.QualityExemptionListResponse;
import ai.core.api.server.quality.QualityExemptionSubjectListRequest;
import ai.core.api.server.quality.QualityExemptionSubjectListResponse;
import ai.core.api.server.quality.QualityExemptionSubjectView;
import ai.core.api.server.quality.QualityExemptionView;
import ai.core.api.server.quality.RemoveQualityExemptionRequest;
import ai.core.server.agent.AgentDefinitionQuery;
import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.AgentNameKey;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.SkillStore;
import ai.core.server.skill.quality.SkillQualityPolicyService;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whitelist administration for the agent and skill publish quality gates. Entries stay inside the
 * immutable policy revisions, so every add or remove activates a new revision exactly like a rule
 * change. The page-level feature only needs this service: entries are resolved back to subjects for
 * display, and new entries are pinned to the subject id so a rename cannot widen the exemption.
 *
 * @author core-ai
 */
public class QualityExemptionAdminService {
    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_SKILL = "SKILL";
    private static final int SUBJECT_LIMIT = 20;

    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    SkillStore skillStore;
    @Inject
    QualityPolicyService agentPolicyService;
    @Inject
    SkillQualityPolicyService skillPolicyService;

    public QualityExemptionListResponse list(String userId) {
        requireAdmin(userId);
        return buildList();
    }

    public QualityExemptionSubjectListResponse subjects(QualityExemptionSubjectListRequest request, String userId) {
        requireAdmin(userId);
        var type = type(request == null ? null : request.type);
        var query = request == null || request.query == null ? "" : request.query.trim();
        var response = new QualityExemptionSubjectListResponse();
        response.subjects = TYPE_AGENT.equals(type) ? agentSubjects(query) : skillSubjects(query);
        return response;
    }

    public QualityExemptionListResponse add(AddQualityExemptionRequest request, String userId) {
        requireAdmin(userId);
        if (request == null) throw new BadRequestException("request is required");
        var type = type(request.type);
        var subjectId = requireText(request.subjectId, "subject_id");
        if (TYPE_AGENT.equals(type)) {
            addAgent(subjectId, request.expectedGeneration, userId);
        } else {
            addSkill(subjectId, request.expectedGeneration, userId);
        }
        return buildList();
    }

    public QualityExemptionListResponse remove(RemoveQualityExemptionRequest request, String userId) {
        requireAdmin(userId);
        if (request == null) throw new BadRequestException("request is required");
        var type = type(request.type);
        var value = requireText(request.value, "value");
        if (TYPE_AGENT.equals(type)) {
            removeAgent(value, request.expectedGeneration, userId);
        } else {
            removeSkill(value, request.expectedGeneration, userId);
        }
        return buildList();
    }

    private QualityExemptionListResponse buildList() {
        var response = new QualityExemptionListResponse();
        var agent = agentPolicyService.active();
        response.agent = group(agent.revision(), agent.generation(), agent.exemptions(), true);
        var skill = skillPolicyService.active();
        response.skill = group(skill.revision(), skill.generation(), skill.exemptions(), false);
        return response;
    }

    private QualityExemptionGroupView group(int revision, int generation, List<String> entries, boolean agentType) {
        var group = new QualityExemptionGroupView();
        group.policyRevision = revision;
        group.generation = generation;
        group.entries = entries.stream()
            .map(value -> agentType ? agentEntry(value) : skillEntry(value))
            .toList();
        return group;
    }

    private void addAgent(String agentId, Integer expectedGeneration, String userId) {
        var agent = agentDefinitionStore.get(agentId)
            .orElseThrow(() -> new BadRequestException("agent not found: " + agentId));
        var entries = new ArrayList<>(agentPolicyService.active().exemptions());
        if (QualityExemptions.match(entries, agent.id, agent.name) != null) {
            throw new BadRequestException("agent is already exempt: " + agent.name);
        }
        entries.add(agent.id);
        agentPolicyService.createPolicy(new QualityPolicyService.ActivatePolicyCommand(null, null, null, null,
            expectedGeneration, "Exempt agent \"" + agent.name + "\" from the publish quality gate",
            null, null, entries), userId);
    }

    private void addSkill(String skillId, Integer expectedGeneration, String userId) {
        var skill = skillStore.get(skillId)
            .orElseThrow(() -> new BadRequestException("skill not found: " + skillId));
        var entries = new ArrayList<>(skillPolicyService.active().exemptions());
        if (QualityExemptions.match(entries, skill.qualifiedName, skill.name) != null) {
            throw new BadRequestException("skill is already exempt: " + skill.qualifiedName);
        }
        entries.add(skill.qualifiedName);
        skillPolicyService.createPolicy(new SkillQualityPolicyService.ActivateCommand(null, null, null, null,
            expectedGeneration, "Exempt skill \"" + skill.qualifiedName + "\" from the publish quality gate",
            null, null, entries), userId);
    }

    private void removeAgent(String value, Integer expectedGeneration, String userId) {
        var entries = new ArrayList<>(agentPolicyService.active().exemptions());
        if (!entries.remove(value)) {
            throw new BadRequestException("quality exemption entry not found: " + value);
        }
        var agent = resolveAgent(value);
        var label = agent != null ? agent.name : value;
        agentPolicyService.createPolicy(new QualityPolicyService.ActivatePolicyCommand(null, null, null, null,
            expectedGeneration, "Remove agent \"" + label + "\" from the quality exemptions", null, null, entries),
            userId);
    }

    private void removeSkill(String value, Integer expectedGeneration, String userId) {
        var entries = new ArrayList<>(skillPolicyService.active().exemptions());
        if (!entries.remove(value)) {
            throw new BadRequestException("quality exemption entry not found: " + value);
        }
        var skill = resolveSkill(value);
        var label = skill != null ? skill.qualifiedName : value;
        skillPolicyService.createPolicy(new SkillQualityPolicyService.ActivateCommand(null, null, null, null,
            expectedGeneration, "Remove skill \"" + label + "\" from the quality exemptions", null, null, entries),
            userId);
    }

    private QualityExemptionView agentEntry(String value) {
        var view = new QualityExemptionView();
        view.value = value;
        var agent = resolveAgent(value);
        if (agent == null) {
            view.resolved = Boolean.FALSE;
            return view;
        }
        view.resolved = Boolean.TRUE;
        view.subjectId = agent.id;
        view.name = agent.name;
        view.detail = agent.id;
        view.automatic = QualityExemptions.builtInAgentReason(agent) != null;
        return view;
    }

    private QualityExemptionView skillEntry(String value) {
        var view = new QualityExemptionView();
        view.value = value;
        var skill = resolveSkill(value);
        if (skill == null) {
            view.resolved = Boolean.FALSE;
            return view;
        }
        view.resolved = Boolean.TRUE;
        view.subjectId = skill.id;
        view.name = skill.name;
        view.detail = skill.qualifiedName;
        view.automatic = Boolean.FALSE;
        return view;
    }

    private AgentDefinition resolveAgent(String value) {
        var byId = agentDefinitionStore.get(value);
        if (byId.isPresent()) return byId.get();
        var query = new AgentDefinitionQuery();
        query.scope = AgentDefinitionQuery.Scope.ALL;
        query.name = value;
        query.excludeSystemPrompt = true;
        query.limit = 1;
        return agentDefinitionStore.findOne(query).orElse(null);
    }

    private SkillDefinition resolveSkill(String value) {
        var byQualifiedName = skillStore.findByQualifiedName(value);
        if (byQualifiedName.isPresent()) return byQualifiedName.get();
        var byName = skillStore.findByName(value);
        return byName.isEmpty() ? null : byName.get(0);
    }

    private List<QualityExemptionSubjectView> agentSubjects(String query) {
        var exemptions = agentPolicyService.active().exemptions();
        var subjects = new ArrayList<QualityExemptionSubjectView>();
        var seen = new HashSet<String>();

        if (!query.isEmpty()) {
            agentDefinitionStore.get(query)
                .filter(agent -> agent.forkedFrom == null)
                .ifPresent(agent -> addAgentSubject(subjects, seen, agent, exemptions));
        }

        var request = new AgentDefinitionQuery();
        request.scope = AgentDefinitionQuery.Scope.ALL;
        request.nameKeyPrefix = AgentNameKey.normalize(query);
        request.forkedFromIsNull = Boolean.TRUE;
        request.summaryOnly = true;
        request.sortField = "name_key";
        request.ascending = true;
        request.limit = SUBJECT_LIMIT;
        for (var agent : agentDefinitionStore.find(request)) {
            addAgentSubject(subjects, seen, agent, exemptions);
        }
        return subjects.size() > SUBJECT_LIMIT ? List.copyOf(subjects.subList(0, SUBJECT_LIMIT)) : subjects;
    }

    private void addAgentSubject(List<QualityExemptionSubjectView> subjects, Set<String> seen,
                                 AgentDefinition agent, List<String> exemptions) {
        if (!seen.add(agent.id)) return;
        var view = new QualityExemptionSubjectView();
        view.id = agent.id;
        view.name = agent.name;
        view.detail = agent.id;
        view.alreadyExempt = QualityExemptions.match(exemptions, agent.id, agent.name) != null;
        view.automatic = QualityExemptions.builtInAgentReason(agent) != null;
        subjects.add(view);
    }

    private List<QualityExemptionSubjectView> skillSubjects(String query) {
        var exemptions = skillPolicyService.active().exemptions();
        var needle = query.toLowerCase(Locale.ROOT);
        return skillStore.listSummaries(null, null, null, null).stream()
            .filter(skill -> needle.isEmpty() || contains(skill.name, needle) || contains(skill.qualifiedName, needle))
            .sorted(Comparator.comparing(skill -> skill.qualifiedName))
            .limit(SUBJECT_LIMIT)
            .map(skill -> {
                var view = new QualityExemptionSubjectView();
                view.id = skill.id;
                view.name = skill.name;
                view.detail = skill.qualifiedName;
                view.alreadyExempt = QualityExemptions.match(exemptions, skill.qualifiedName, skill.name) != null;
                view.automatic = Boolean.FALSE;
                return view;
            })
            .toList();
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void requireAdmin(String userId) {
        agentPolicyService.requireAdmin(userId);
    }

    private String type(String value) {
        if (value == null) throw new BadRequestException("exemption type is required");
        var type = value.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_AGENT.equals(type) && !TYPE_SKILL.equals(type)) {
            throw new BadRequestException("invalid exemption type: " + value);
        }
        return type;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException(field + " is required");
        return value.trim();
    }
}
