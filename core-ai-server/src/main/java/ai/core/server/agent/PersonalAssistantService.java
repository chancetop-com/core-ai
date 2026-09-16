package ai.core.server.agent;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.User;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Template and fork ownership for the shared default assistant.
 *
 * <p>{@code default-assistant} stays one admin-editable template record, while every platform user works on a
 * private copy (id = {@code assistant:<userId>}) forked on first use, so memory and personalization stay per user.
 * The template keeps serving callers that cannot own an agent (system, api users).
 *
 * @author Xander
 */
public class PersonalAssistantService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalAssistantService.class);
    public static final String DEFAULT_ASSISTANT_TEMPLATE_ID = "default-assistant";
    private static final Set<String> FORKABLE_TEMPLATE_IDS = Set.of(DEFAULT_ASSISTANT_TEMPLATE_ID);
    private static final String PERSONAL_ASSISTANT_ID_PREFIX = "assistant:";
    private static final String SYSTEM_USER_ID = "system";
    private static final String USER_TYPE_API = "api";
    private static final String ASSISTANT_NAME_SUFFIX = "'s Assistant";
    private static final int DUPLICATE_KEY_CODE = 11000;
    private static final int MAX_NAME_ATTEMPTS = 10;

    public static boolean isForkableTemplate(String agentId) {
        return agentId != null && FORKABLE_TEMPLATE_IDS.contains(agentId);
    }

    public static Set<String> forkableTemplateIds() {
        return FORKABLE_TEMPLATE_IDS;
    }

    public static boolean isPersonalAssistant(AgentDefinition definition) {
        return definition != null && definition.forkedFrom != null;
    }

    public static String personalAssistantId(String userId) {
        return PERSONAL_ASSISTANT_ID_PREFIX + userId;
    }

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    SkillService skillService;

    /** Maps a template id to the caller's own copy, forking on first use; any other id passes through. */
    public String resolve(String agentId, String userId) {
        if (!isForkableTemplate(agentId)) return agentId;
        var personal = findPersonalAssistant(userId);
        if (personal != null) return personal.id;
        var user = forkableUser(userId);
        if (user == null) return agentId;
        var template = agentDefinitionCollection.get(agentId).orElse(null);
        if (template == null) return agentId;
        return fork(template, user).id;
    }

    public AgentDefinition findPersonalAssistant(String userId) {
        if (userId == null || userId.isBlank()) return null;
        return agentDefinitionCollection.get(personalAssistantId(userId)).orElse(null);
    }

    /**
     * The caller's own copy of the given template, forked on first use.
     *
     * @return null when the caller has to keep reading the shared template, e.g. system and api users
     */
    public AgentDefinition findOrFork(AgentDefinition template, String userId) {
        if (template == null || !isForkableTemplate(template.id)) return null;
        var personal = findPersonalAssistant(userId);
        if (personal != null) return personal;
        var user = forkableUser(userId);
        return user == null ? null : fork(template, user);
    }

    private User forkableUser(String userId) {
        if (userId == null || userId.isBlank() || SYSTEM_USER_ID.equals(userId)) return null;
        var user = userCollection.get(userId).orElse(null);
        return user != null && !USER_TYPE_API.equals(user.userType) ? user : null;
    }

    // Concurrent first uses race on the deterministic id, the loser re-reads the winner's document.
    private AgentDefinition fork(AgentDefinition template, User user) {
        var fork = buildFork(template, user);
        try {
            agentDefinitionCollection.insert(fork);
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            var existing = findPersonalAssistant(user.id);
            if (existing == null) throw new IllegalStateException("failed to fork personal assistant, userId=" + user.id, e);
            return existing;
        }
        grantAgentPermission(user, fork.id);
        // the copy is the user's everyday entry point, so it starts pinned above the rest of the selector
        AgentListHelper.addFavorite(userCollection, user.id, fork.id);
        LOGGER.info("personal assistant forked, userId={}, agentId={}, templateId={}", user.id, fork.id, template.id);
        return fork;
    }

    private AgentDefinition buildFork(AgentDefinition template, User user) {
        var config = templateConfig(template);
        var now = ZonedDateTime.now();
        var fork = new AgentDefinition();
        fork.id = personalAssistantId(user.id);
        fork.userId = user.id;
        fork.name = assistantName(user);
        fork.nameKey = AgentNameKey.normalize(fork.name);
        fork.description = template.description;
        fork.type = DefinitionType.AGENT;
        fork.status = AgentStatus.PUBLISHED;
        fork.forkedFrom = template.id;
        fork.forkedAt = now;
        fork.publishedAt = now;
        fork.createdAt = now;
        fork.updatedAt = now;
        fork.updatedBy = user.id;
        applyConfig(fork, config);
        fork.publishedConfig = AgentExecutableConfigFactory.fromEditableDefinition(fork);
        AgentDependencyAccessPolicy.markPublishedSkillsValidated(fork.publishedConfig);
        return fork;
    }

    // The fork owns its snapshot: the template prompt is already resolved into the config, dataset records and
    // prompt documents are shared by agent id, and memory has to start empty per user.
    private AgentPublishedConfig templateConfig(AgentDefinition template) {
        var source = template.publishedConfig != null
            ? template.publishedConfig
            : AgentExecutableConfigFactory.fromEditableDefinition(template);
        var config = AgentExecutableConfigFactory.fromPublishedConfig(source);
        config.systemPromptId = null;
        config.datasetConfig = null;
        config.enableMemory = Boolean.TRUE;
        config.tools = usableTools(config.tools);
        config.skillIds = usableSkillIds(config.skillIds);
        config.subAgentIds = usableSubAgentIds(config.subAgentIds);
        return config;
    }

    private void applyConfig(AgentDefinition fork, AgentPublishedConfig config) {
        fork.systemPrompt = config.systemPrompt;
        fork.systemPromptId = config.systemPromptId;
        fork.model = config.model;
        fork.multiModalModel = config.multiModalModel;
        fork.preferCaptionPath = config.preferCaptionPath;
        fork.temperature = config.temperature;
        fork.thinkingEffort = config.thinkingEffort;
        fork.maxTurns = config.maxTurns;
        fork.timeoutSeconds = config.timeoutSeconds;
        fork.tools = config.tools;
        fork.skillIds = IdLists.cleanOrNull(config.skillIds);
        fork.subAgentIds = IdLists.cleanOrNull(config.subAgentIds);
        fork.inputTemplate = config.inputTemplate;
        fork.variables = config.variables;
        fork.responseSchema = config.responseSchema;
        fork.enableMemory = config.enableMemory;
        fork.sandboxConfig = config.sandboxConfig;
        fork.datasetConfig = config.datasetConfig;
    }

    private List<ToolRef> usableTools(List<ToolRef> tools) {
        if (tools == null || tools.isEmpty()) return null;
        var usable = new ArrayList<ToolRef>(tools.size());
        for (var tool : tools) {
            if (AgentDependencyAccessPolicy.isLlmCallRef(tool) && !hasUsableLlmCall(tool)) {
                LOGGER.warn("dropping unavailable llm call tool while forking personal assistant, toolId={}", tool.id);
                continue;
            }
            usable.add(tool);
        }
        return usable.isEmpty() ? null : usable;
    }

    private boolean hasUsableLlmCall(ToolRef ref) {
        var definitionId = llmCallDefinitionId(ref);
        if (definitionId == null) return false;
        var definition = agentDefinitionCollection.get(definitionId).orElse(null);
        return AgentDependencyAccessPolicy.hasUsablePublishedLlmCall(definition);
    }

    private String llmCallDefinitionId(ToolRef ref) {
        if (ref == null || ref.id == null || !ref.id.startsWith(ToolRef.LLM_CALL_PREFIX)) return null;
        var definitionId = ref.id.substring(ToolRef.LLM_CALL_PREFIX.length());
        return definitionId.isBlank() || !definitionId.equals(definitionId.trim()) ? null : definitionId;
    }

    private List<String> usableSkillIds(List<String> skillIds) {
        var cleaned = IdLists.clean(skillIds);
        if (cleaned.isEmpty()) return null;
        var usable = new ArrayList<String>(cleaned.size());
        for (var skillId : cleaned) {
            if (skillExists(skillId)) {
                usable.add(skillId);
            } else {
                LOGGER.warn("dropping unavailable skill while forking personal assistant, skillId={}", skillId);
            }
        }
        return usable.isEmpty() ? null : usable;
    }

    private boolean skillExists(String skillId) {
        try {
            return skillService.get(skillId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<String> usableSubAgentIds(List<String> subAgentIds) {
        var cleaned = IdLists.clean(subAgentIds);
        if (cleaned.isEmpty()) return null;
        var usable = new ArrayList<String>(cleaned.size());
        for (var subAgentId : cleaned) {
            var definition = agentDefinitionCollection.get(subAgentId).orElse(null);
            if (AgentDependencyAccessPolicy.hasUsablePublishedSubAgent(definition)) {
                usable.add(subAgentId);
            } else {
                LOGGER.warn("dropping unavailable sub agent while forking personal assistant, subAgentId={}", subAgentId);
            }
        }
        return usable.isEmpty() ? null : usable;
    }

    private String assistantName(User user) {
        var base = (displayName(user) + ASSISTANT_NAME_SUFFIX).trim();
        var name = base;
        for (int attempt = 2; attempt <= MAX_NAME_ATTEMPTS && nameExists(user.id, name); attempt++) {
            name = base + " " + attempt;
        }
        return name;
    }

    private boolean nameExists(String userId, String name) {
        return agentDefinitionCollection.findOne(Filters.and(
            Filters.eq("user_id", userId),
            Filters.eq("name", name))).isPresent();
    }

    private String displayName(User user) {
        if (user.name != null && !user.name.isBlank()) return user.name.trim();
        var email = user.email;
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return user.id;
    }

    // Permission whitelists are opt-in: users without any configured permission stay unrestricted, but
    // whitelisted users have to see their own copy or every other agent would become unreachable for them.
    private void grantAgentPermission(User user, String agentId) {
        if (user.permissions == null || user.permissions.isEmpty()) return;
        for (var permission : user.permissions) {
            if (PermissionService.RESOURCE_TYPE_AGENT.equals(permission.resourceType) && agentId.equals(permission.resourceId)) return;
        }
        var permission = new Document("resource_type", PermissionService.RESOURCE_TYPE_AGENT).append("resource_id", agentId);
        userCollection.update(Filters.eq("_id", user.id), Updates.addToSet("permissions", permission));
    }
}
