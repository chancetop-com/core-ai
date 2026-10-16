package ai.core.server.comparison;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.util.IdLists;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * The adoption side of a comparison test: overwrite the live agent with a frozen variant through the
 * normal publish flow (every validation still applies), and undo that overwrite while nobody has touched
 * the agent since. The comparison record itself is loaded and persisted by {@link AgentComparisonService}.
 *
 * @author stephen
 */
public class ComparisonAdoptionService {
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    AgentDefinitionService agentDefinitionService;

    /**
     * Applies a frozen variant to the live agent. The config it replaces is snapshotted first, so an
     * accidental adoption stays revertable until somebody touches the agent again.
     */
    public void adopt(AgentComparison comparison, String adopt, boolean force, String userId) {
        var agent = agentDefinitionCollection.get(comparison.agentId).orElse(null);
        if (agent == null) throw new BadRequestException("agent is unavailable");
        if (!force && !Objects.equals(agent.publishedAt, comparison.agentPublishedAt)) {
            throw new BadRequestException("agent was modified after the comparison started; retry with force to overwrite");
        }
        var variant = ComparisonVariantDefinitionFactory.variantOf(comparison, adopt);
        if (variant == null || variant.config == null) throw new BadRequestException("variant is unavailable");
        comparison.preAdoptConfig = AgentExecutableConfigFactory.fromEditableDefinition(agent);
        applyConfigAndPublish(agent, variant.config, userId);
        agentDefinitionCollection.get(comparison.agentId)
            .ifPresent(published -> comparison.adoptAgentUpdatedAt = published.updatedAt);
    }

    /**
     * Republishes the snapshot taken right before the adoption. Refuses once the agent was edited or
     * republished since (updatedAt fingerprint), so a revert can never silently overwrite newer work.
     */
    public void revert(AgentComparison comparison, String userId) {
        var agent = agentDefinitionCollection.get(comparison.agentId).orElse(null);
        if (agent == null) throw new BadRequestException("agent is unavailable");
        if (!Objects.equals(agent.updatedAt, comparison.adoptAgentUpdatedAt)) {
            throw new BadRequestException("agent changed after the adoption; the revert snapshot is no longer safe to publish");
        }
        applyConfigAndPublish(agent, comparison.preAdoptConfig, userId);
    }

    /**
     * Assign the editable fields from the snapshot, then run the normal publish flow so every validation
     * (skill accessibility, dependencies, system-default admin) still applies.
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
}
