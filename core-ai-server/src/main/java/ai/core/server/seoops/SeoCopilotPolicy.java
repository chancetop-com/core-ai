package ai.core.server.seoops;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;

/**
 * Fail-closed capability check for the advisory-only SEO Copilot.
 *
 * @author xander
 */
public class SeoCopilotPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(SeoCopilotPolicy.class);

    @Inject
    MongoCollection<AgentDefinition> agentCollection;

    @Inject
    SeoOpsRuntimeConfig runtimeConfig;

    public Optional<String> eligibleAgentId() {
        var id = runtimeConfig.copilotAgentId();
        if (!runtimeConfig.enabled() || id == null) return Optional.empty();
        var agent = agentCollection.get(id).orElse(null);
        if (agent == null) return reject(id, "missing");
        if (agent.type != DefinitionType.AGENT) return reject(id, "not_agent");
        if (agent.status != AgentStatus.PUBLISHED) return reject(id, "not_published");
        if (!safe(agent.publishedConfig)) return reject(id, "unsafe_capabilities");
        return Optional.of(id);
    }

    private boolean safe(AgentPublishedConfig config) {
        return config != null
            && config.sandboxConfig == null
            && !Boolean.TRUE.equals(config.enableMemory)
            && empty(config.tools)
            && empty(config.skillIds)
            && empty(config.subAgentIds)
            && empty(config.datasetConfig);
    }

    private boolean empty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    private Optional<String> reject(String id, String reason) {
        LOGGER.warn("SEO Copilot disabled, agentId={}, reason={}", id, reason);
        return Optional.empty();
    }
}
