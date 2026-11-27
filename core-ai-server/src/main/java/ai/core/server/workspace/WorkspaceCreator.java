package ai.core.server.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Validates, names and inserts workspace rows. Volume provisioning stays with the caller: a failed
 * PVC never loses the row, the name stays reserved and the user can retry.
 *
 * @author stephen
 */
class WorkspaceCreator {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceCreator.class);

    private final WorkspaceStore workspaces;
    private final WorkspaceVolumeManager volumeManager;
    private final WorkspaceAudit audit;
    private final WorkspaceConfig config;

    WorkspaceCreator(WorkspaceStore workspaces, WorkspaceVolumeManager volumeManager, WorkspaceAudit audit,
                     WorkspaceConfig config) {
        this.workspaces = workspaces;
        this.volumeManager = volumeManager;
        this.audit = audit;
        this.config = config;
    }

    WorkspaceRecord create(String ownerUserId, String name, String agentId, Integer sizeGb, String profileId,
                           Integer idleSleepMinutes) {
        return create(ownerUserId, new WorkspaceCreateSpec(name, agentId, sizeGb, profileId, idleSleepMinutes, null, null));
    }

    WorkspaceRecord create(String ownerUserId, WorkspaceCreateSpec spec) {
        var workspaceName = spec.name() == null ? "" : spec.name().trim();
        if (workspaceName.isEmpty() || workspaceName.length() > 64) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "workspace name must be 1-64 characters");
        }
        var size = spec.sizeGb() != null ? spec.sizeGb() : config.defaultSizeGb;
        if (size <= 0 || size > config.maxSizeGb) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED, "workspace size must be between 1 and " + config.maxSizeGb + " GB");
        }
        WorkspaceQuotas.checkCreate(config, workspaces.listByOwner(ownerUserId), size);
        var workspace = WorkspaceRecord.create(ownerUserId, workspaceName, spec.agentId());
        workspace.sizeGb = size;
        workspace.storageClass = config.storageClass;
        workspace.image = config.image;
        workspace.idleSleepMinutes = validatedIdleSleep(spec.idleSleepMinutes());
        workspace.bootstrapScript = spec.bootstrapScript();
        workspace.startScript = spec.startScript();
        try {
            workspace.profile = WorkspaceProfile.of(spec.profileId());
        } catch (IllegalArgumentException e) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, e.getMessage(), e);
        }
        if (!workspaces.insertIfAbsent(workspace)) {
            throw new WorkspaceException(WorkspaceException.NAME_CONFLICT, "workspace name already exists: " + workspaceName);
        }
        audit.append(workspace.id, "created", ownerUserId, Map.of("sizeGb", size, "profile", workspace.profile.id));
        try {
            volumeManager.ensure(workspace);
        } catch (WorkspaceProvisioningException e) {
            LOGGER.warn("workspace volume provisioning failed, workspace={}", workspace.id, e);
        }
        return workspace;
    }

    /**
     * Resolves the default workspace of (user, agent), creating it when absent. The name is not user
     * input, so a conflict moves to the next candidate instead of failing the session.
     */
    WorkspaceRecord resolveDefault(String ownerUserId, String agentId, Integer sizeGb, String profileId) {
        if (agentId == null || agentId.isBlank()) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "agent id is required for a default workspace");
        }
        var existing = workspaces.findDefault(ownerUserId, agentId);
        if (existing.isPresent() && !existing.get().deleted()) return existing.get();
        var size = sizeGb != null && sizeGb > 0 ? Math.min(sizeGb, config.maxSizeGb) : config.defaultSizeGb;
        WorkspaceQuotas.checkCreate(config, workspaces.listByOwner(ownerUserId), size);
        WorkspaceProfile profile;
        try {
            profile = WorkspaceProfile.of(profileId);
        } catch (IllegalArgumentException e) {
            // agent config is not a lifecycle request: an unknown profile falls back instead of failing the session
            LOGGER.warn("unknown workspace profile in agent config, agent={}, profile={}", agentId, profileId);
            profile = WorkspaceProfile.DEFAULT;
        }
        for (var name : defaultNames(agentId)) {
            var workspace = WorkspaceRecord.create(ownerUserId, name, agentId);
            workspace.isDefault = true;
            workspace.sizeGb = size;
            workspace.profile = profile;
            workspace.storageClass = config.storageClass;
            workspace.image = config.image;
            workspace.idleSleepMinutes = config.idleSleepMinutes;
            if (workspaces.insertIfAbsent(workspace)) {
                audit.append(workspace.id, "created", ownerUserId, Map.of("sizeGb", size, "default", Boolean.TRUE));
                return workspace;
            }
            // another request may have created the default first, or the name belongs to a detached workspace
            var concurrent = workspaces.findDefault(ownerUserId, agentId);
            if (concurrent.isPresent() && !concurrent.get().deleted()) return concurrent.get();
        }
        throw new WorkspaceException(WorkspaceException.NAME_CONFLICT,
                "cannot allocate a default workspace name for agent: " + agentId);
    }

    private List<String> defaultNames(String agentId) {
        var slug = agentId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 24) slug = slug.substring(0, 24);
        return List.of("default", "default-" + (slug.isEmpty() ? "agent" : slug),
                "default-" + Long.toHexString(ThreadLocalRandom.current().nextLong()));
    }

    int validatedIdleSleep(Integer idleSleepMinutes) {
        if (idleSleepMinutes == null) return config.idleSleepMinutes;
        if (idleSleepMinutes < 1 || idleSleepMinutes > 1440) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "idle sleep minutes must be between 1 and 1440");
        }
        return idleSleepMinutes;
    }

    void rename(String ownerUserId, WorkspaceRecord workspace, String name) {
        var trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "workspace name must be 1-64 characters");
        }
        if (trimmed.equals(workspace.name)) return;
        if (workspaces.findByName(ownerUserId, trimmed).isPresent()) {
            throw new WorkspaceException(WorkspaceException.NAME_CONFLICT, "workspace name already exists: " + trimmed);
        }
        workspace.name = trimmed;
    }
}
