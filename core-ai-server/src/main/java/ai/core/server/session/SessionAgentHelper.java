package ai.core.server.session;

import ai.core.api.server.session.SessionConfig;
import ai.core.media.MediaProvider;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.messaging.SessionOwnershipRegistry;
import core.framework.inject.Inject;

import java.util.List;

/**
 * Session creation helpers extracted from AgentSessionManager to keep the file under the line limit.
 *
 * @author stephen
 */
public class SessionAgentHelper {
    @Inject
    MediaProvider mediaProvider;
    @Inject
    SessionOwnershipRegistry ownershipRegistry;

    List<AgentDatasetConfig> resolveDatasetConfig(AgentDefinition definition, SessionConfig config, SessionConfig overrides) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (overrides != null && overrides.datasetConfigs != null && !overrides.datasetConfigs.isEmpty()) {
            datasetConfig = overrides.datasetConfigs.stream().map(entry -> {
                var perm = new AgentDatasetConfig();
                perm.datasetId = entry.datasetId;
                perm.permission = DatasetPermission.valueOf(entry.permission);
                perm.isOutput = entry.isOutput;
                return perm;
            }).toList();
            config.datasetConfigs = overrides.datasetConfigs;
        } else if (overrides != null && overrides.datasetId != null && !overrides.datasetId.isBlank()) {
            var overridePerm = new AgentDatasetConfig();
            overridePerm.datasetId = overrides.datasetId;
            overridePerm.permission = DatasetPermission.READ;
            datasetConfig = List.of(overridePerm);
            config.datasetId = overrides.datasetId;
        }
        return datasetConfig;
    }

    boolean claimOwnership(String sessionId) {
        return ownershipRegistry == null || ownershipRegistry.claim(sessionId);
    }

    boolean claimOrConfirmOwnership(String sessionId) {
        return ownershipRegistry == null || ownershipRegistry.isOwner(sessionId) || ownershipRegistry.claim(sessionId);
    }

    void renewSessionOwnership(String sessionId) {
        if (ownershipRegistry != null) ownershipRegistry.claimOrRenew(sessionId);
    }

    void releaseOwnership(String sessionId) {
        if (ownershipRegistry != null) ownershipRegistry.release(sessionId);
    }
}
