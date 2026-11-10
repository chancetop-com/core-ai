package ai.core.server.agent;

import ai.core.api.server.agent.AgentImportConfigView;
import ai.core.api.server.agent.AgentSkillRefView;
import ai.core.api.server.agent.AgentSubAgentRefView;
import ai.core.api.server.agent.AgentUnresolvedReferenceView;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ExportAgentResponse;
import ai.core.api.server.agent.ImportAgentResponse;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.util.IdLists;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Exports an agent definition to a portable envelope and imports one back. Environment-bound references
 * (skills, sub-agents) are exported by name and resolved against the target environment; references that
 * do not resolve are dropped and reported instead of failing the whole import.
 */
public class AgentPortService {
    public static final String EXPORT_FORMAT = "core-ai-agent-export/v1";

    private static AgentUnresolvedReferenceView toView(AgentUnresolvedReference reference) {
        var view = new AgentUnresolvedReferenceView();
        view.refType = reference.refType();
        view.refId = reference.refId();
        view.refName = reference.refName();
        view.message = reference.message();
        return view;
    }

    // Parse + validate the envelope without touching Mongo, so it is unit-testable. A bad envelope is a
    // client error (400) and no draft is created.
    static ExportAgentResponse parseEnvelope(String content) {
        if (Strings.isBlank(content) || "null".equals(content.trim())) {
            throw new BadRequestException("agent export file is empty", "INVALID_IMPORT");
        }
        ExportAgentResponse envelope;
        try {
            envelope = JSON.fromJSON(ExportAgentResponse.class, content);
        } catch (RuntimeException e) {
            throw new BadRequestException("invalid agent export file: not valid JSON", "INVALID_IMPORT", e);
        }
        if (envelope == null || Strings.isBlank(envelope.name)) {
            throw new BadRequestException("agent export is missing a name", "INVALID_IMPORT");
        }
        if (envelope.format != null && !EXPORT_FORMAT.equals(envelope.format)) {
            throw new BadRequestException("unrecognized agent export format: " + envelope.format);
        }
        return envelope;
    }

    @Inject
    AgentDefinitionService definitionService;

    @Inject
    SkillService skillService;

    @Inject
    SystemPromptService systemPromptService;

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    public ExportAgentResponse export(String id) {
        AgentDefinition definition = definitionService.getEntity(id);
        var response = new ExportAgentResponse();
        response.format = EXPORT_FORMAT;
        response.exportedAt = ZonedDateTime.now();
        response.name = definition.name;
        response.description = definition.description;
        response.type = definition.type != null ? definition.type.name() : DefinitionType.AGENT.name();
        response.systemPrompt = systemPrompt(definition);
        response.model = definition.model;
        response.multiModalModel = definition.multiModalModel;
        response.preferCaptionPath = definition.preferCaptionPath;
        response.temperature = definition.temperature;
        response.thinkingEffort = AgentViewHelper.normalizeThinkingEffort(definition.thinkingEffort);
        response.maxTurns = definition.maxTurns;
        response.timeoutSeconds = definition.timeoutSeconds;
        response.tools = AgentViewHelper.toToolRefViews(definition.tools);
        response.skills = skillRefs(definition.skillIds);
        response.subagents = subAgentRefs(definition.subAgentIds);
        response.inputTemplate = definition.inputTemplate;
        response.variables = definition.variables;
        response.responseSchema = definition.responseSchema;
        response.enableMemory = definition.enableMemory;
        return response;
    }

    public ImportAgentResponse importAgent(String content, String name, boolean preview, String userId) {
        ExportAgentResponse envelope = parseEnvelope(content);
        List<AgentUnresolvedReference> unresolved = new ArrayList<>();
        List<String> skillIds = resolveSkillIds(envelope, unresolved);
        List<String> subAgentIds = resolveSubAgentIds(envelope, userId, unresolved);
        AgentImportConfigView config = toConfig(envelope,
            !Strings.isBlank(name) ? name.trim() : envelope.name, skillIds, subAgentIds);

        var response = new ImportAgentResponse();
        response.config = config;
        response.unresolvedReferences = unresolved.stream().map(AgentPortService::toView).toList();
        if (preview) return response;

        CreateAgentRequest request = toCreateRequest(config);
        request.name = uniqueName(config.name, userId);
        config.name = request.name;
        response.agent = definitionService.create(request, userId);
        return response;
    }

    // A managed prompt is exported as its resolved content: prompt ids are environment-bound and the
    // importing deployment may not have the same template. A deleted template falls back to the inline copy.
    private String systemPrompt(AgentDefinition definition) {
        if (Strings.isBlank(definition.systemPromptId)) return definition.systemPrompt;
        try {
            return systemPromptService.resolveContent(definition.systemPromptId);
        } catch (RuntimeException e) {
            return definition.systemPrompt;
        }
    }

    private List<AgentSkillRefView> skillRefs(List<String> skillIds) {
        var refs = new ArrayList<AgentSkillRefView>();
        for (String id : IdLists.clean(skillIds)) {
            var ref = new AgentSkillRefView();
            ref.id = id;
            SkillDefinition skill = skillService.findOrNull(id);
            if (skill != null) {
                ref.qualifiedName = skill.qualifiedName;
                ref.name = skill.name;
            }
            refs.add(ref);
        }
        return refs;
    }

    private List<AgentSubAgentRefView> subAgentRefs(List<String> subAgentIds) {
        var refs = new ArrayList<AgentSubAgentRefView>();
        for (String id : IdLists.clean(subAgentIds)) {
            var ref = new AgentSubAgentRefView();
            ref.id = id;
            ref.name = agentDefinitionCollection.get(id).map(agent -> agent.name).orElse(null);
            refs.add(ref);
        }
        return refs;
    }

    private List<String> resolveSkillIds(ExportAgentResponse envelope, List<AgentUnresolvedReference> unresolved) {
        var ids = new LinkedHashSet<String>();
        if (envelope.skills != null) {
            for (AgentSkillRefView ref : envelope.skills) {
                SkillDefinition skill = resolveSkill(ref);
                if (skill != null) {
                    ids.add(skill.id);
                } else {
                    unresolved.add(new AgentUnresolvedReference("SKILL", ref.id,
                        !Strings.isBlank(ref.qualifiedName) ? ref.qualifiedName : ref.name,
                        "skill is unavailable in this environment"));
                }
            }
        }
        if (envelope.skillIds != null) {
            for (String id : IdLists.clean(envelope.skillIds)) {
                if (ids.contains(id)) continue;
                if (skillService.findOrNull(id) != null) {
                    ids.add(id);
                } else {
                    unresolved.add(new AgentUnresolvedReference("SKILL", id, id, "skill is unavailable in this environment"));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private SkillDefinition resolveSkill(AgentSkillRefView ref) {
        if (ref == null) return null;
        SkillDefinition skill = skillService.findOrNull(ref.id);
        if (skill != null) return skill;
        skill = skillService.findByQualifiedNameOrNull(ref.qualifiedName);
        if (skill != null) return skill;
        if (Strings.isBlank(ref.name)) return null;
        var matches = skillService.findByName(ref.name);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private List<String> resolveSubAgentIds(ExportAgentResponse envelope, String userId,
                                            List<AgentUnresolvedReference> unresolved) {
        var ids = new LinkedHashSet<String>();
        if (envelope.subagents != null) {
            for (AgentSubAgentRefView ref : envelope.subagents) {
                AgentDefinition agent = resolveSubAgent(ref, userId);
                if (agent != null) {
                    ids.add(agent.id);
                } else {
                    unresolved.add(new AgentUnresolvedReference("SUBAGENT", ref.id,
                        !Strings.isBlank(ref.name) ? ref.name : ref.id,
                        "sub-agent is unavailable in this environment"));
                }
            }
        }
        if (envelope.subAgentIds != null) {
            for (String id : IdLists.clean(envelope.subAgentIds)) {
                if (ids.contains(id)) continue;
                AgentDefinition agent = agentDefinitionCollection.get(id).orElse(null);
                if (canReference(agent, userId)) {
                    ids.add(id);
                } else {
                    unresolved.add(new AgentUnresolvedReference("SUBAGENT", id, id, "sub-agent is unavailable in this environment"));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private AgentDefinition resolveSubAgent(AgentSubAgentRefView ref, String userId) {
        if (ref == null) return null;
        AgentDefinition agent = ref.id != null ? agentDefinitionCollection.get(ref.id).orElse(null) : null;
        if (canReference(agent, userId)) return agent;
        if (Strings.isBlank(ref.name)) return null;
        agent = agentDefinitionCollection.findOne(Filters.and(
            Filters.eq("user_id", userId),
            Filters.eq("name_key", AgentNameKey.normalize(ref.name)))).orElse(null);
        return canReference(agent, userId) ? agent : null;
    }

    private boolean canReference(AgentDefinition agent, String userId) {
        return agent != null
            && (AgentDependencyAccessPolicy.isOwnedEditable(agent, userId)
            || AgentDependencyAccessPolicy.hasUsablePublishedSubAgent(agent));
    }

    private String uniqueName(String requested, String userId) {
        String base = Strings.isBlank(requested) ? "Imported agent" : requested.trim();
        String candidate = base;
        int suffix = 1;
        while (agentDefinitionCollection.findOne(Filters.and(
            Filters.eq("user_id", userId),
            Filters.eq("name", candidate))).isPresent()) {
            candidate = base + " (" + suffix++ + ")";
        }
        return candidate;
    }

    private AgentImportConfigView toConfig(ExportAgentResponse envelope, String name, List<String> skillIds,
                                           List<String> subAgentIds) {
        var config = new AgentImportConfigView();
        config.name = name;
        config.description = envelope.description;
        config.type = validType(envelope.type);
        config.systemPrompt = envelope.systemPrompt;
        config.model = envelope.model;
        config.multiModalModel = envelope.multiModalModel;
        config.preferCaptionPath = envelope.preferCaptionPath;
        config.temperature = envelope.temperature;
        config.thinkingEffort = envelope.thinkingEffort;
        config.maxTurns = envelope.maxTurns;
        config.timeoutSeconds = envelope.timeoutSeconds;
        config.tools = envelope.tools;
        config.skillIds = skillIds.isEmpty() ? null : skillIds;
        config.subAgentIds = subAgentIds.isEmpty() ? null : subAgentIds;
        config.inputTemplate = envelope.inputTemplate;
        config.variables = envelope.variables;
        config.responseSchema = envelope.responseSchema;
        config.enableMemory = envelope.enableMemory;
        return config;
    }

    private String validType(String type) {
        if (Strings.isBlank(type)) return DefinitionType.AGENT.name();
        try {
            return DefinitionType.valueOf(type.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("agent export has an invalid type: " + type, "INVALID_IMPORT", e);
        }
    }

    private CreateAgentRequest toCreateRequest(AgentImportConfigView config) {
        var request = new CreateAgentRequest();
        request.name = config.name;
        request.description = config.description;
        request.type = config.type;
        request.systemPrompt = config.systemPrompt;
        request.model = config.model;
        request.multiModalModel = config.multiModalModel;
        request.preferCaptionPath = config.preferCaptionPath;
        request.temperature = config.temperature;
        request.thinkingEffort = config.thinkingEffort;
        request.maxTurns = config.maxTurns;
        request.timeoutSeconds = config.timeoutSeconds;
        request.tools = config.tools;
        request.skillIds = config.skillIds;
        request.subAgentIds = config.subAgentIds;
        request.inputTemplate = config.inputTemplate;
        request.variables = config.variables;
        request.responseSchema = config.responseSchema;
        request.enableMemory = config.enableMemory;
        return request;
    }

    public record AgentUnresolvedReference(String refType, String refId, String refName, String message) {
    }
}
