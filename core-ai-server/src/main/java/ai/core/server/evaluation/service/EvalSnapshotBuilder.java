package ai.core.server.evaluation.service;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.evaluation.EvalHashing;
import ai.core.server.evaluation.domain.CandidateSnapshotEntity;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.util.IdLists;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Freezes the exact agent configuration an evaluation run executes. The first backend intentionally
 * supports only tool-free, skill-free, dataset-free offline agents.
 *
 * @author stephen
 */
public class EvalSnapshotBuilder {
    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    SystemPromptService systemPromptService;
    @Inject
    PermissionService permissionService;
    @Inject
    EvaluationStore evaluationStore;

    public CandidateSnapshotEntity build(EvaluationEntity evaluation, EvalEnums.Source source, String actorId) {
        var definition = agentDefinitionStore.get(evaluation.agentId)
            .orElseThrow(() -> new EvalValidationException("agentId", "agent not found: " + evaluation.agentId));
        permissionService.check(actorId, PermissionService.RESOURCE_TYPE_AGENT, definition.id);
        var config = configFor(definition, source, actorId);
        requireOfflineCapable(config);
        var body = new EvalBodies.CandidateSnapshotBody();
        body.agentId = definition.id;
        body.source = source.name();
        body.sourceRevision = definition.definitionRevision != null
            ? "revision:" + definition.definitionRevision
            : "updated:" + (definition.updatedAt == null ? "unknown" : definition.updatedAt);
        body.name = definition.name;
        body.description = definition.description;
        body.definitionType = definition.type.name();
        body.systemPrompt = resolveSystemPrompt(config);
        body.model = config.model;
        body.multiModalModel = config.multiModalModel;
        body.temperature = config.temperature;
        body.thinkingEffort = config.thinkingEffort;
        body.maxTurns = config.maxTurns;
        body.timeoutSeconds = config.timeoutSeconds;
        body.inputTemplate = config.inputTemplate;
        body.variables = config.variables;
        body.responseSchema = config.responseSchema;
        body.memoryEnabled = Boolean.FALSE;
        body.modelVersionPinned = Boolean.FALSE;
        body.capabilityNotes = new ArrayList<>();
        body.capabilityNotes.add("memory is disabled for isolated evaluation trials");
        body.capabilityNotes.add("the upstream model version may roll; strict reproducibility is not guaranteed");
        var fingerprint = EvalHashing.hashObject(body);
        for (var existing : evaluationStore.listSnapshots(evaluation.id)) {
            if (fingerprint.equals(existing.fingerprint)) return existing;
        }
        var entity = new CandidateSnapshotEntity();
        entity.id = UUID.randomUUID().toString();
        entity.evaluationId = evaluation.id;
        entity.agentId = definition.id;
        entity.source = source.name();
        entity.sourceRevision = body.sourceRevision;
        entity.fingerprint = fingerprint;
        entity.snapshotJson = JsonUtil.toJson(body);
        entity.createdBy = actorId;
        entity.createdAt = ZonedDateTime.now();
        evaluationStore.insertSnapshot(entity);
        return entity;
    }

    public AgentDefinition detachedDefinition(EvalBodies.CandidateSnapshotBody snapshot, String actorId,
                                              int maxTurns, int timeoutSeconds) {
        var definition = new AgentDefinition();
        definition.id = snapshot.agentId;
        definition.userId = actorId;
        definition.name = snapshot.name;
        definition.description = snapshot.description;
        definition.type = DefinitionType.valueOf(snapshot.definitionType == null ? "AGENT" : snapshot.definitionType);
        definition.status = AgentStatus.PUBLISHED;
        var config = new AgentPublishedConfig();
        config.systemPrompt = snapshot.systemPrompt;
        config.systemPromptId = null;
        config.model = snapshot.model;
        config.multiModalModel = snapshot.multiModalModel;
        config.temperature = snapshot.temperature;
        config.thinkingEffort = snapshot.thinkingEffort;
        config.maxTurns = maxTurns;
        config.timeoutSeconds = timeoutSeconds;
        config.inputTemplate = snapshot.inputTemplate;
        config.variables = snapshot.variables;
        config.responseSchema = snapshot.responseSchema;
        config.enableMemory = Boolean.FALSE;
        definition.publishedConfig = config;
        var sandbox = new AgentSandboxConfig();
        sandbox.enabled = Boolean.FALSE;
        definition.sandboxConfig = sandbox;
        return definition;
    }

    private AgentPublishedConfig configFor(AgentDefinition definition, EvalEnums.Source source, String actorId) {
        if (source == EvalEnums.Source.DRAFT) {
            if (!AgentDependencyAccessPolicy.isOwnedEditable(definition, actorId)) {
                throw new EvalValidationException("source", "draft source requires the agent owner");
            }
            if (definition.type != DefinitionType.AGENT) {
                throw new EvalValidationException("source", "only AGENT definitions are supported in this phase");
            }
            return AgentExecutableConfigFactory.fromEditableDefinition(definition);
        }
        if (definition.status != AgentStatus.PUBLISHED || definition.publishedConfig == null
            || !AgentDependencyAccessPolicy.hasValidatedPublishedSkills(definition.publishedConfig)) {
            throw new EvalValidationException("source", "agent has no usable published configuration");
        }
        if (definition.type != DefinitionType.AGENT) {
            throw new EvalValidationException("source", "only AGENT definitions are supported in this phase");
        }
        return AgentExecutableConfigFactory.fromPublishedConfig(definition.publishedConfig);
    }

    private void requireOfflineCapable(AgentPublishedConfig config) {
        var gaps = new ArrayList<String>();
        if (config.tools != null && !config.tools.isEmpty()) gaps.add("tools");
        if (!IdLists.clean(config.skillIds).isEmpty()) gaps.add("skills");
        if (!IdLists.clean(config.subAgentIds).isEmpty()) gaps.add("subagents");
        if (config.datasetConfig != null && !config.datasetConfig.isEmpty()) gaps.add("datasets");
        if (config.sandboxConfig != null) gaps.add("sandbox");
        if (!gaps.isEmpty()) {
            throw new EvalValidationException("source",
                "OFFLINE_TEXT cannot execute agent capabilities: " + String.join(", ", gaps)
                    + "; remove them or wait for the isolated sandbox profile");
        }
    }

    private String resolveSystemPrompt(AgentPublishedConfig config) {
        if (config.systemPromptId != null && !config.systemPromptId.isBlank()) {
            try {
                return systemPromptService.resolveContent(config.systemPromptId);
            } catch (RuntimeException e) {
                throw new EvalValidationException("source", "managed prompt is unavailable: " + config.systemPromptId, e);
            }
        }
        return config.systemPrompt;
    }
}
