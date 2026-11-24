package ai.core.server.workspace;

import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Workspace lifecycle: the durable volume is provisioned once and survives every runtime, while
 * the runtime (Sandbox CR) is created on wake and deleted on sleep. Volume faults mark
 * {@code volume_status=ERROR} and stop there; runtime faults mark {@code runtime_status=ERROR} and
 * keep the already-provisioned volume, so a scheduling failure never looks like data loss.
 *
 * @author stephen
 */
public class WorkspaceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceStore workspaces;
    private final WorkspaceVolumeStore volumes;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceEventStore events;
    private final WorkspaceProvisioner provisioner;
    private final WorkspaceRuntimeManager runtimes;
    private final WorkspaceConfig config;
    private final Executor wakeExecutor;

    public WorkspaceService(WorkspaceStore workspaces, WorkspaceVolumeStore volumes, WorkspaceBindingStore bindings,
                            WorkspaceEventStore events, WorkspaceProvisioner provisioner,
                            WorkspaceRuntimeManager runtimes, WorkspaceConfig config, Executor wakeExecutor) {
        this.workspaces = workspaces;
        this.volumes = volumes;
        this.bindings = bindings;
        this.events = events;
        this.provisioner = provisioner;
        this.runtimes = runtimes;
        this.config = config;
        this.wakeExecutor = wakeExecutor;
    }

    public WorkspaceRecord create(String ownerUserId, String name, String agentId, Integer sizeGb, String profileId) {
        requireEnabled();
        var workspaceName = name == null ? "" : name.trim();
        if (workspaceName.isEmpty() || workspaceName.length() > 64) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "workspace name must be 1-64 characters");
        }
        var size = sizeGb != null ? sizeGb : config.defaultSizeGb;
        if (size <= 0 || size > config.maxSizeGb) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED, "workspace size must be between 1 and " + config.maxSizeGb + " GB");
        }
        checkCreateQuota(ownerUserId, size);

        var workspace = WorkspaceRecord.create(ownerUserId, workspaceName, agentId);
        workspace.sizeGb = size;
        workspace.storageClass = config.storageClass;
        workspace.image = config.image;
        workspace.idleSleepMinutes = config.idleSleepMinutes;
        try {
            workspace.profile = WorkspaceProfile.of(profileId);
        } catch (IllegalArgumentException e) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, e.getMessage());
        }
        if (!workspaces.insertIfAbsent(workspace)) {
            throw new WorkspaceException(WorkspaceException.NAME_CONFLICT, "workspace name already exists: " + workspaceName);
        }
        appendEvent(workspace.id, "created", ownerUserId, Map.of("sizeGb", size, "profile", workspace.profile.id));
        try {
            provisionVolume(workspace);
        } catch (WorkspaceProvisioningException e) {
            // the workspace row stays: the name is reserved and the user can retry provisioning
            LOGGER.warn("workspace volume provisioning failed, workspace={}", workspace.id, e);
        }
        return load(ownerUserId, workspace.id);
    }

    public WorkspaceRecord get(String ownerUserId, String id) {
        requireEnabled();
        return load(ownerUserId, id);
    }

    public List<WorkspaceRecord> list(String ownerUserId) {
        requireEnabled();
        return workspaces.listByOwner(ownerUserId);
    }

    public WorkspaceRecord update(String ownerUserId, String id, String name, Integer idleSleepMinutes) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        if (name != null) {
            var trimmed = name.trim();
            if (trimmed.isEmpty() || trimmed.length() > 64) {
                throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "workspace name must be 1-64 characters");
            }
            if (!trimmed.equals(workspace.name)) {
                if (workspaces.findByName(ownerUserId, trimmed).isPresent()) {
                    throw new WorkspaceException(WorkspaceException.NAME_CONFLICT, "workspace name already exists: " + trimmed);
                }
                workspace.name = trimmed;
            }
        }
        if (idleSleepMinutes != null) {
            if (idleSleepMinutes < 1 || idleSleepMinutes > 1440) {
                throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "idle sleep minutes must be between 1 and 1440");
            }
            workspace.idleSleepMinutes = idleSleepMinutes;
        }
        workspace.updatedAt = ZonedDateTime.now();
        workspaces.update(workspace);
        appendEvent(id, "updated", ownerUserId, Map.of());
        return load(ownerUserId, id);
    }

    /** explicit retry of a failed volume; idempotent for a healthy one */
    public WorkspaceRecord ensureVolume(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        try {
            provisionVolume(workspace);
        } catch (WorkspaceProvisioningException e) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, e.getMessage(), e);
        }
        return load(ownerUserId, id);
    }

    /**
     * API wake: claims PROVISIONING synchronously so callers see a state change immediately, then
     * provisions the runtime in the background. The frontend polls {@link #get} until READY.
     */
    public WorkspaceRecord wakeAsync(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        ensureRuntimeSupported();
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.READY
                || workspace.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
            workspaces.touchLastUsed(id, ZonedDateTime.now());
            return load(ownerUserId, id);
        }
        workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.PROVISIONING,
                workspace.runtimeGeneration + 1, null, null);
        wakeExecutor.execute(() -> {
            try {
                wake(ownerUserId, id);
            } catch (RuntimeException e) {
                // wake() already recorded runtime_status=ERROR and the error code; nothing else to do here
                LOGGER.warn("background workspace wake failed, workspace={}", id, e);
            }
        });
        return load(ownerUserId, id);
    }

    public WorkspaceRecord wake(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        ensureRuntimeSupported();
        WorkspaceVolumeRecord volume;
        try {
            volume = provisionVolume(workspace);
        } catch (WorkspaceProvisioningException e) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, e.getMessage(), e);
        }

        var existingBinding = bindings.get(id);
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.READY && existingBinding.isPresent()) {
            workspaces.touchLastUsed(id, ZonedDateTime.now());
            return load(ownerUserId, id);
        }
        if (existingBinding.isPresent()) {
            // stale pointer (server crash, controller eviction): the CR is replaceable, the PVC is not
            deleteRuntimeQuietly(existingBinding.get().sandboxId);
            bindings.delete(id);
        }
        checkRunningQuota(ownerUserId, id);

        var runtimeGeneration = workspace.runtimeGeneration + 1;
        workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.PROVISIONING, runtimeGeneration, null, null);

        var runtime = new WorkspaceRuntimeSpec();
        runtime.workspaceId = id;
        runtime.ownerUserId = ownerUserId;
        runtime.image = workspace.image != null ? workspace.image : config.image;
        runtime.pvcName = volume.pvcName;
        runtime.runtimeGeneration = runtimeGeneration;
        runtime.profile = workspace.profile;
        runtime.maxRuntimeSeconds = WorkspaceDefaults.MAX_RUNTIME_SECONDS;

        var sandboxName = runtimes.name(runtime);
        try {
            runtimes.create(runtime);
            runtimes.waitReady(sandboxName, config.wakeTimeoutSeconds);
        } catch (RuntimeException e) {
            deleteRuntimeQuietly(sandboxName);
            workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.ERROR, runtimeGeneration,
                    WorkspaceException.WAKE_FAILED, e.getMessage());
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED, "workspace runtime failed to start: " + e.getMessage(), e);
        }

        var now = ZonedDateTime.now();
        var binding = WorkspaceBindingRecord.create(id, sandboxName, provisioner.provider(), runtimeGeneration);
        binding.runtimeStartedAt = now;
        bindings.upsert(binding);
        workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.READY, runtimeGeneration, null, null);
        workspaces.touchLastUsed(id, now);
        appendEvent(id, "woken", ownerUserId, Map.of("sandboxId", sandboxName, "runtimeGeneration", runtimeGeneration));
        return load(ownerUserId, id);
    }

    public void sleep(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        stopRuntime(workspace);
        appendEvent(id, "slept", ownerUserId, Map.of());
    }

    /**
     * Replacement of the durable environment. The old volume is kept in DELETING (its PVC name and
     * failure history stay readable) while a fresh generation is provisioned; sessions keep pointing
     * at the same workspace id and simply see an empty environment.
     */
    public WorkspaceRecord reset(String ownerUserId, String id, String confirmName) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        if (confirmName == null || !confirmName.equals(workspace.name)) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "reset requires confirm_name to match the workspace name");
        }
        stopRuntime(workspace);

        var oldGeneration = workspace.activeVolumeGeneration;
        if (oldGeneration != null) {
            var oldVolume = volumes.get(id, oldGeneration).orElse(null);
            if (oldVolume != null) {
                volumes.markDeleted(id, oldGeneration, WorkspaceVolumeStatus.DELETING);
                try {
                    provisioner.deleteVolume(oldVolume);
                    volumes.markDeleted(id, oldGeneration, WorkspaceVolumeStatus.DELETED);
                } catch (WorkspaceProvisioningException e) {
                    throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE,
                            "cannot delete the previous workspace volume, reset aborted", e);
                }
            }
        }

        var generation = oldGeneration == null ? 0 : oldGeneration + 1;
        var volume = WorkspaceVolumeRecord.provisioning(workspace, generation,
                provisioner.volumeName(id, generation), provisioner.provider());
        if (!volumes.insertIfAbsent(volume)) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, "workspace volume generation already exists: " + generation);
        }
        try {
            provisioner.ensureVolume(workspace, generation);
            volumes.updateStatus(id, generation, WorkspaceVolumeStatus.READY);
            workspaces.updateVolumeState(id, WorkspaceVolumeStatus.READY, generation, null, null);
        } catch (WorkspaceProvisioningException e) {
            volumes.updateStatus(id, generation, WorkspaceVolumeStatus.ERROR);
            workspaces.updateVolumeState(id, WorkspaceVolumeStatus.ERROR, generation, e.errorCode, e.getMessage());
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, e.getMessage(), e);
        }
        appendEvent(id, "reset", ownerUserId, Map.of("volumeGeneration", generation));
        return load(ownerUserId, id);
    }

    public void delete(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        stopRuntime(workspace);
        if (!workspaces.softDelete(id, ZonedDateTime.now())) {
            throw new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace not found: " + id);
        }
        appendEvent(id, "deleted", ownerUserId, Map.of());
    }

    public WorkspaceRecord restore(String ownerUserId, String id) {
        requireEnabled();
        var workspace = workspaces.get(id)
                .filter(w -> w.ownerUserId.equals(ownerUserId))
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace not found: " + id));
        if (!workspace.deleted()) return workspace;
        try {
            if (!workspaces.restore(id)) {
                throw new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace not found: " + id);
            }
        } catch (WorkspaceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkspaceException(WorkspaceException.NAME_CONFLICT, "workspace name is already in use", e);
        }
        appendEvent(id, "restored", ownerUserId, Map.of());
        return load(ownerUserId, id);
    }

    /** single-session binding of a READY runtime; a different active session gets WORKSPACE_BUSY */
    public WorkspaceBindingRecord bind(String ownerUserId, String id, String sessionId) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        if (workspace.runtimeStatus != WorkspaceRuntimeStatus.READY) {
            throw new WorkspaceException(WorkspaceException.WAKING, "workspace runtime is not ready");
        }
        var binding = bindings.get(id)
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.WAKING, "workspace runtime is not ready"));
        if (!bindings.bindSession(id, binding.sandboxId, binding.runtimeGeneration, sessionId, ZonedDateTime.now())) {
            throw new WorkspaceException(WorkspaceException.BUSY, "workspace is used by another session");
        }
        workspaces.touchLastUsed(id, ZonedDateTime.now());
        appendEvent(id, "bound", sessionId, Map.of("runtimeGeneration", binding.runtimeGeneration));
        return bindings.get(id).orElse(binding);
    }

    public void detach(String id, String sessionId) {
        bindings.clearSessionIfBound(id, sessionId, ZonedDateTime.now());
    }

    private WorkspaceVolumeRecord provisionVolume(WorkspaceRecord workspace) {
        var volume = volumes.active(workspace.id).orElse(null);
        if (volume == null) {
            var generation = workspace.activeVolumeGeneration != null ? workspace.activeVolumeGeneration : 0L;
            var created = WorkspaceVolumeRecord.provisioning(workspace, generation,
                    provisioner.volumeName(workspace.id, generation), provisioner.provider());
            if (volumes.insertIfAbsent(created)) {
                volume = created;
            } else {
                volume = volumes.active(workspace.id)
                        .orElseThrow(() -> new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE,
                                "workspace volume is already being provisioned elsewhere"));
            }
        }
        try {
            provisioner.ensureVolume(workspace, volume.volumeGeneration);
            volumes.updateStatus(workspace.id, volume.volumeGeneration, WorkspaceVolumeStatus.READY);
            workspaces.updateVolumeState(workspace.id, WorkspaceVolumeStatus.READY, volume.volumeGeneration, null, null);
            volume.status = WorkspaceVolumeStatus.READY;
            return volume;
        } catch (WorkspaceProvisioningException e) {
            volumes.updateStatus(workspace.id, volume.volumeGeneration, WorkspaceVolumeStatus.ERROR);
            workspaces.updateVolumeState(workspace.id, WorkspaceVolumeStatus.ERROR, volume.volumeGeneration,
                    e.errorCode, e.getMessage());
            throw e;
        }
    }

    private void stopRuntime(WorkspaceRecord workspace) {
        var binding = bindings.get(workspace.id).orElse(null);
        if (binding != null) {
            deleteRuntimeQuietly(binding.sandboxId);
            bindings.delete(workspace.id);
        }
        if (workspace.runtimeStatus != WorkspaceRuntimeStatus.NONE) {
            workspaces.updateRuntimeState(workspace.id, WorkspaceRuntimeStatus.NONE, workspace.runtimeGeneration, null, null);
        }
    }

    private void deleteRuntimeQuietly(String sandboxName) {
        if (sandboxName == null || sandboxName.isBlank()) return;
        try {
            runtimes.delete(sandboxName);
        } catch (RuntimeException e) {
            // the controller's own shutdown policy will remove it; the volume was never owned by the CR
            LOGGER.warn("failed to delete workspace sandbox {}, leaving it to the controller", sandboxName, e);
        }
    }

    private void checkCreateQuota(String ownerUserId, int sizeGb) {
        var owned = workspaces.listByOwner(ownerUserId);
        if (owned.size() >= config.maxWorkspacesPerUser) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED,
                    "workspace limit reached (" + config.maxWorkspacesPerUser + ")");
        }
        var totalGb = owned.stream().mapToInt(w -> w.sizeGb).sum() + sizeGb;
        if (totalGb > config.maxTotalGbPerUser) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED,
                    "total workspace storage limit reached (" + config.maxTotalGbPerUser + " GB)");
        }
    }

    private void checkRunningQuota(String ownerUserId, String excludeId) {
        var running = workspaces.listByOwner(ownerUserId).stream()
                .filter(w -> !Objects.equals(w.id, excludeId))
                .filter(w -> w.runtimeStatus == WorkspaceRuntimeStatus.READY || w.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING)
                .count();
        if (running >= config.maxRunningPerUser) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED,
                    "running workspace limit reached (" + config.maxRunningPerUser + ")");
        }
    }

    private WorkspaceRecord load(String ownerUserId, String id) {
        return workspaces.get(id)
                .filter(w -> w.ownerUserId.equals(ownerUserId))
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace not found: " + id));
    }

    private WorkspaceRecord loadActive(String ownerUserId, String id) {
        var workspace = load(ownerUserId, id);
        if (workspace.deleted()) {
            throw new WorkspaceException(WorkspaceException.DELETED, "workspace is deleted: " + id);
        }
        return workspace;
    }

    private void requireEnabled() {
        if (!config.enabled) {
            throw new WorkspaceException(WorkspaceException.UNSUPPORTED, "persistent workspace is not enabled");
        }
    }

    private void ensureRuntimeSupported() {
        if (!runtimes.available()) {
            throw new WorkspaceException(WorkspaceException.UNSUPPORTED, "workspace runtime provider is not configured");
        }
    }

    private void appendEvent(String workspaceId, String type, String actor, Map<String, Object> payload) {
        try {
            events.append(WorkspaceEventRecord.of(workspaceId, type, actor, JSON.toJSON(payload)));
        } catch (RuntimeException e) {
            // audit is best-effort: a failed event insert must not fail the lifecycle operation
            LOGGER.warn("failed to append workspace event, workspace={}, type={}", workspaceId, type, e);
        }
    }
}
