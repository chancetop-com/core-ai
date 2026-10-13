package ai.core.server.comparison;

import ai.core.api.server.comparison.AgentComparisonView;
import ai.core.api.server.comparison.CreateAgentComparisonRequest;
import ai.core.api.server.comparison.EndAgentComparisonRequest;
import ai.core.api.server.comparison.ListAgentComparisonsRequest;
import ai.core.api.server.comparison.ListAgentComparisonsResponse;
import ai.core.api.server.comparison.StartAgentComparisonRequest;
import ai.core.api.server.comparison.UpdateAgentComparisonRequest;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.AgentVersion;
import ai.core.server.comparison.domain.ComparisonStatus;
import ai.core.server.comparison.domain.ComparisonVariant;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.User;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle of an agent version comparison: create (A = current published config, B = copy), edit the
 * draft, start (freeze variants + effective tools), end with optional adopt through the normal publish
 * flow. Session pairs and voting live in {@link ComparisonSessionService}.
 *
 * @author stephen
 */
public class AgentComparisonService {
    private static final String LABEL_A = "版本 A（当前线上）";
    private static final String LABEL_B = "版本 B（候选）";

    private static String normalizeRef(String versionId) {
        return Strings.isBlank(versionId) ? null : versionId;
    }

    @Inject
    MongoCollection<AgentComparison> comparisonCollection;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    MongoCollection<AgentVersion> versionCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    SkillService skillService;
    @Inject
    ComparisonViewAssembler assembler;

    public AgentComparisonView create(CreateAgentComparisonRequest request, String userId) {
        if (request == null || request.agentId == null || request.agentId.isBlank()) {
            throw new BadRequestException("agent_id required");
        }
        var agent = agentDefinitionService.getEntity(request.agentId);
        requireAgentOwnerOrAdmin(agent, userId);
        if (agent.type != DefinitionType.AGENT) {
            throw new BadRequestException("only chat agents support version comparison");
        }
        if (!AgentDependencyAccessPolicy.hasUsablePublishedConfig(agent)) {
            throw new BadRequestException("agent must be published before starting a comparison");
        }
        if (runningComparison(agent.id, null).isPresent()) {
            throw new BadRequestException("agent already has a running comparison");
        }
        var comparison = new AgentComparison();
        comparison.id = UUID.randomUUID().toString();
        comparison.agentId = agent.id;
        comparison.agentName = agent.name;
        comparison.agentForkedFrom = agent.forkedFrom;
        comparison.status = ComparisonStatus.DRAFT;
        comparison.versionAId = normalizeRef(request.versionAId);
        comparison.versionBId = normalizeRef(request.versionBId);
        var sourceA = comparison.versionAId != null
            ? requireVersionConfig(agent.id, comparison.versionAId)
            : AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig);
        var sourceB = comparison.versionBId != null
            ? requireVersionConfig(agent.id, comparison.versionBId)
            : AgentExecutableConfigFactory.fromPublishedConfig(sourceA);
        comparison.variantA = newVariant(LABEL_A, sourceA);
        comparison.variantB = newVariant(LABEL_B, sourceB);
        comparison.participantUserIds = ComparisonAccess.cleanParticipants(null, userId);
        comparison.createdBy = userId;
        comparison.createdAt = ZonedDateTime.now();
        comparison.updatedAt = comparison.createdAt;
        comparisonCollection.insert(comparison);
        return assembler.toDetailView(comparison, true);
    }

    public ListAgentComparisonsResponse list(ListAgentComparisonsRequest request, String userId) {
        if (request == null || request.agentId == null || request.agentId.isBlank()) {
            throw new BadRequestException("agent_id required");
        }
        var agent = agentDefinitionService.getEntity(request.agentId);
        requireAgentOwnerOrAdmin(agent, userId);
        var query = new Query();
        query.filter = Filters.eq("agent_id", agent.id);
        query.sort = Sorts.descending("created_at");
        query.limit = 100;
        var response = new ListAgentComparisonsResponse();
        response.comparisons = comparisonCollection.find(query).stream().map(assembler::toListView).toList();
        return response;
    }

    /**
     * Chat banner probe: returns the running comparison with its id only for its participants; other
     * callers learn nothing beyond an empty view, so they stay on the regular chat path.
     */
    public AgentComparisonView running(String agentId, String userId) {
        if (agentId == null || agentId.isBlank()) throw new BadRequestException("agent_id required");
        var view = new AgentComparisonView();
        view.agentId = agentId;
        var comparison = runningComparison(agentId, null).orElse(null);
        if (comparison == null) return view;
        if (ComparisonAccess.isParticipant(comparison, userId) || ComparisonAccess.canManage(userCollection, comparison, userId)) {
            view.id = comparison.id;
            view.status = comparison.status.name();
            view.agentName = comparison.agentName;
        }
        return view;
    }

    public AgentComparisonView get(String id, String userId) {
        var comparison = requireComparison(id);
        var manager = ComparisonAccess.canManage(userCollection, comparison, userId);
        if (!manager && !ComparisonAccess.isParticipant(comparison, userId)) {
            throw new ForbiddenException("comparison is unavailable");
        }
        return assembler.toDetailView(comparison, manager);
    }

    public AgentComparisonView update(String id, UpdateAgentComparisonRequest request, String userId) {
        var comparison = requireComparison(id);
        ComparisonAccess.requireManage(userCollection, comparison, userId);
        if (comparison.status == ComparisonStatus.ENDED) {
            throw new BadRequestException("comparison has ended");
        }
        if (request != null) {
            applyUpdate(comparison, request);
        }
        comparison.updatedAt = ZonedDateTime.now();
        comparisonCollection.replace(comparison);
        return assembler.toDetailView(comparison, true);
    }

    private void applyUpdate(AgentComparison comparison, UpdateAgentComparisonRequest request) {
        if (comparison.status == ComparisonStatus.RUNNING) {
            if (request.variantB != null || request.effectiveToolsA != null || request.effectiveToolsB != null
                || request.versionAId != null || request.versionBId != null) {
                throw new BadRequestException("variants and the effective tool set are frozen once the comparison is running");
            }
        } else {
            if (request.variantB != null) ComparisonViews.applyConfigPatch(comparison.variantB.config, request.variantB);
            if (request.versionAId != null || request.versionBId != null) switchSources(comparison, request);
            if (request.effectiveToolsA != null) freezeToolList(comparison.variantA, request.effectiveToolsA);
            if (request.effectiveToolsB != null) freezeToolList(comparison.variantB, request.effectiveToolsB);
        }
        if (request.participantUserIds != null) {
            comparison.participantUserIds = ComparisonAccess.cleanParticipants(request.participantUserIds, comparison.createdBy);
        }
        if (request.budgetUsd != null) comparison.budgetUsd = request.budgetUsd;
    }

    /** A source switch re-snapshots both sides and suggests a fresh tool set. */
    private void switchSources(AgentComparison comparison, UpdateAgentComparisonRequest request) {
        if (request.versionAId != null) comparison.versionAId = normalizeRef(request.versionAId);
        if (request.versionBId != null) comparison.versionBId = normalizeRef(request.versionBId);
        var agent = agentDefinitionCollection.get(comparison.agentId)
            .orElseThrow(() -> new BadRequestException("agent is unavailable"));
        var sourceA = comparison.versionAId != null
            ? requireVersionConfig(comparison.agentId, comparison.versionAId)
            : AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig);
        var sourceB = comparison.versionBId != null
            ? requireVersionConfig(comparison.agentId, comparison.versionBId)
            : AgentExecutableConfigFactory.fromPublishedConfig(sourceA);
        comparison.variantA = newVariant(LABEL_A, sourceA);
        comparison.variantB = newVariant(LABEL_B, sourceB);
    }

    public AgentComparisonView start(String id, StartAgentComparisonRequest request, String userId) {
        var comparison = requireComparison(id);
        ComparisonAccess.requireManage(userCollection, comparison, userId);
        if (comparison.status != ComparisonStatus.DRAFT) {
            throw new BadRequestException("comparison is not a draft");
        }
        if (comparison.participantUserIds == null || comparison.participantUserIds.isEmpty()) {
            throw new BadRequestException("at least one participant is required");
        }
        if (runningComparison(comparison.agentId, comparison.id).isPresent()) {
            throw new BadRequestException("agent already has a running comparison");
        }
        var agent = agentDefinitionCollection.get(comparison.agentId)
            .orElseThrow(() -> new BadRequestException("agent is unavailable"));
        refreshForStart(comparison, agent);
        freezeVariant(comparison.variantA, request != null ? request.effectiveToolsA : null, userId);
        freezeVariant(comparison.variantB, request != null ? request.effectiveToolsB : null, userId);
        comparison.agentName = agent.name;
        comparison.agentForkedFrom = agent.forkedFrom;
        comparison.agentPublishedAt = agent.publishedAt;
        comparison.status = ComparisonStatus.RUNNING;
        comparison.startedAt = ZonedDateTime.now();
        comparison.updatedAt = comparison.startedAt;
        comparisonCollection.replace(comparison);
        return assembler.toDetailView(comparison, true);
    }

    public AgentComparisonView end(String id, EndAgentComparisonRequest request, String userId) {
        var comparison = requireComparison(id);
        ComparisonAccess.requireManage(userCollection, comparison, userId);
        if (comparison.status == ComparisonStatus.DRAFT) {
            throw new BadRequestException("comparison has not started");
        }
        if (comparison.status == ComparisonStatus.ENDED) {
            return assembler.toDetailView(comparison, true);
        }
        var adopt = request != null ? request.adopt : null;
        if (adopt != null && !"a".equals(adopt) && !"b".equals(adopt)) {
            throw new BadRequestException("adopt must be a, b or null");
        }
        if (adopt != null) {
            adoptVariant(comparison, adopt, request != null && Boolean.TRUE.equals(request.force), userId);
        }
        comparison.adoptedVariant = adopt;
        comparison.status = ComparisonStatus.ENDED;
        comparison.endedAt = ZonedDateTime.now();
        comparison.updatedAt = comparison.endedAt;
        comparisonCollection.replace(comparison);
        return assembler.toDetailView(comparison, true);
    }

    private void adoptVariant(AgentComparison comparison, String adopt, boolean force, String userId) {
        var agent = agentDefinitionCollection.get(comparison.agentId).orElse(null);
        if (agent == null) throw new BadRequestException("agent is unavailable");
        if (!force && !Objects.equals(agent.publishedAt, comparison.agentPublishedAt)) {
            throw new BadRequestException("agent was modified after the comparison started; retry with force to overwrite");
        }
        var variant = ComparisonVariantDefinitionFactory.variantOf(comparison, adopt);
        if (variant == null || variant.config == null) throw new BadRequestException("variant is unavailable");
        applyConfigAndPublish(agent, variant.config, userId);
    }

    /**
     * Adopt a frozen variant: assign the editable fields from the snapshot, then run the normal publish
     * flow so every validation (skill accessibility, dependencies, system-default admin) still applies.
     */
    private void applyConfigAndPublish(AgentDefinition entity, AgentPublishedConfig config, String userId) {
        entity.systemPrompt = config.systemPrompt;
        entity.systemPromptId = null;
        entity.model = config.model;
        entity.multiModalModel = config.multiModalModel;
        entity.preferCaptionPath = config.preferCaptionPath;
        entity.temperature = config.temperature;
        entity.thinkingEffort = config.thinkingEffort;
        entity.maxTurns = config.maxTurns;
        entity.timeoutSeconds = config.timeoutSeconds;
        entity.tools = config.tools != null ? List.copyOf(config.tools) : null;
        entity.skillIds = IdLists.cleanOrNull(config.skillIds);
        entity.subAgentIds = IdLists.cleanOrNull(config.subAgentIds);
        entity.inputTemplate = config.inputTemplate;
        entity.variables = config.variables;
        entity.responseSchema = config.responseSchema;
        entity.enableMemory = config.enableMemory;
        entity.sandboxConfig = config.sandboxConfig;
        entity.datasetConfig = config.datasetConfig;
        entity.updatedAt = ZonedDateTime.now();
        entity.updatedBy = userId;
        agentDefinitionCollection.replace(entity);
        agentDefinitionService.publish(entity.id, userId);
    }

    private ComparisonVariant newVariant(String label, AgentPublishedConfig config) {
        var variant = new ComparisonVariant();
        variant.label = label;
        variant.config = config;
        variant.effectiveTools = ComparisonToolFreeze.defaultEffective(config.tools);
        return variant;
    }

    private void freezeToolList(ComparisonVariant variant, List<ToolRefView> requested) {
        var configured = ComparisonToolFreeze.normalizeAll(variant.config.tools);
        variant.effectiveTools = ComparisonToolFreeze.resolve(requested, variant.effectiveTools, configured);
    }

    private void freezeVariant(ComparisonVariant variant, List<ToolRefView> requestedTools, String userId) {
        freezeToolList(variant, requestedTools);
        // the config may have been re-snapshotted from a version: tools confirmed against the previous
        // content must not silently survive a source change
        ComparisonToolFreeze.requireSubset(variant.effectiveTools, variant.config.tools);
        var skillIds = IdLists.clean(variant.config.skillIds);
        if (!skillIds.isEmpty()) {
            // publish parity: a variant must resolve its skills for every tester, not only for its author
            skillService.resolveAccessibleSkills(skillIds, userId);
        }
        validateExecutableDependencies(variant);
    }

    /**
     * Re-snapshots each side from its source right before freezing: variant A follows the live published
     * config when it has no version; variant B follows its version when one is set. A ref-less variant B
     * keeps the draft's own config (the direct edit flow). Confirmed tool sets are preserved here and
     * validated by the freeze step.
     */
    private void refreshForStart(AgentComparison comparison, AgentDefinition agent) {
        var sourceA = comparison.versionAId != null
            ? requireVersionConfig(comparison.agentId, comparison.versionAId)
            : AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig);
        comparison.variantA.config = sourceA;
        if (comparison.versionBId != null) {
            comparison.variantB.config = requireVersionConfig(comparison.agentId, comparison.versionBId);
        }
    }

    private AgentPublishedConfig requireVersionConfig(String agentId, String versionId) {
        var version = versionCollection.get(versionId)
            .orElseThrow(() -> new BadRequestException("version is unavailable, id=" + versionId));
        if (!agentId.equals(version.agentId)) throw new BadRequestException("version does not belong to the agent");
        return AgentExecutableConfigFactory.fromPublishedConfig(version.config);
    }

    private void validateExecutableDependencies(ComparisonVariant variant) {
        for (var tool : variant.effectiveTools) {
            if (!AgentDependencyAccessPolicy.isLlmCallRef(tool)) continue;
            String definitionId;
            try {
                definitionId = AgentDependencyAccessPolicy.requireLlmCallDefinitionId(tool);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("variant has an unavailable LLM call dependency", "BAD_REQUEST", e);
            }
            var dependency = agentDefinitionCollection.get(definitionId).orElse(null);
            if (!AgentDependencyAccessPolicy.hasUsablePublishedLlmCall(dependency)) {
                throw new BadRequestException("variant has an unavailable LLM call dependency");
            }
        }
        for (var subAgentId : IdLists.clean(variant.config.subAgentIds)) {
            var dependency = agentDefinitionCollection.get(subAgentId).orElse(null);
            if (!AgentDependencyAccessPolicy.hasUsablePublishedSubAgent(dependency)) {
                throw new BadRequestException("variant has an unavailable sub-agent dependency");
            }
        }
    }

    private Optional<AgentComparison> runningComparison(String agentId, String excludeId) {
        var comparison = comparisonCollection.findOne(Filters.and(
            Filters.eq("agent_id", agentId),
            Filters.eq("status", ComparisonStatus.RUNNING.name()))).orElse(null);
        if (comparison == null || comparison.id.equals(excludeId)) return Optional.empty();
        return Optional.of(comparison);
    }

    private AgentComparison requireComparison(String id) {
        return comparisonCollection.get(id)
            .orElseThrow(() -> new NotFoundException("comparison not found, id=" + id));
    }

    private void requireAgentOwnerOrAdmin(AgentDefinition agent, String userId) {
        if (userId != null && (userId.equals(agent.userId) || ComparisonAccess.isAdmin(userCollection, userId))) return;
        throw new ForbiddenException("only the agent owner or admin can manage comparisons");
    }
}
