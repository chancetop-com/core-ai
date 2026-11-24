package ai.core.server.workspace;

import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final WorkspaceBindingStore bindings;
    private final WorkspaceEventStore events;
    private final WorkspaceProvisioner provisioner;
    private final WorkspaceVolumeManager volumeManager;
    private final WorkspaceRuntimeManager runtimes;
    private final WorkspaceConfig config;
    private final Executor wakeExecutor;

    public WorkspaceService(WorkspaceStores stores, WorkspaceProvisioner provisioner,
                            WorkspaceRuntimeManager runtimes, WorkspaceConfig config, Executor wakeExecutor) {
        this.workspaces = stores.workspaces();
        this.bindings = stores.bindings();
        this.events = stores.events();
        this.provisioner = provisioner;
        this.volumeManager = new WorkspaceVolumeManager(stores.volumes(), stores.workspaces(), provisioner);
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
        WorkspaceQuotas.checkCreate(config, workspaces.listByOwner(ownerUserId), size);

        var workspace = WorkspaceRecord.create(ownerUserId, workspaceName, agentId);
        workspace.sizeGb = size;
        workspace.storageClass = config.storageClass;
        workspace.image = config.image;
        workspace.idleSleepMinutes = config.idleSleepMinutes;
        try {
            workspace.profile = WorkspaceProfile.of(profileId);
        } catch (IllegalArgumentException e) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, e.getMessage(), e);
        }
        if (!workspaces.insertIfAbsent(workspace)) {
            throw new WorkspaceException(WorkspaceException.NAME_CONFLICT, "workspace name already exists: " + workspaceName);
        }
        appendEvent(workspace.id, "created", ownerUserId, Map.of("sizeGb", size, "profile", workspace.profile.id));
        try {
            volumeManager.ensure(workspace);
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

    public boolean enabled() {
        return config.enabled;
    }

    /**
     * Resolves the default workspace of (user, agent), creating it when absent. The name is not user
     * input, so a conflict moves to the next candidate instead of failing the session.
     */
    public WorkspaceRecord resolveDefault(String ownerUserId, String agentId) {
        requireEnabled();
        if (agentId == null || agentId.isBlank()) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "agent id is required for a default workspace");
        }
        var existing = workspaces.findDefault(ownerUserId, agentId);
        if (existing.isPresent() && !existing.get().deleted()) return existing.get();

        var size = config.defaultSizeGb;
        WorkspaceQuotas.checkCreate(config, workspaces.listByOwner(ownerUserId), size);
        for (var name : defaultNames(agentId)) {
            var workspace = WorkspaceRecord.create(ownerUserId, name, agentId);
            workspace.isDefault = true;
            workspace.sizeGb = size;
            workspace.storageClass = config.storageClass;
            workspace.image = config.image;
            workspace.idleSleepMinutes = config.idleSleepMinutes;
            if (workspaces.insertIfAbsent(workspace)) {
                appendEvent(workspace.id, "created", ownerUserId, Map.of("sizeGb", size, "default", true));
                return load(ownerUserId, workspace.id);
            }
            // another request may have created the default first, or the name belongs to a detached workspace
            var concurrent = workspaces.findDefault(ownerUserId, agentId);
            if (concurrent.isPresent() && !concurrent.get().deleted()) return concurrent.get();
        }
        throw new WorkspaceException(WorkspaceException.NAME_CONFLICT,
                "cannot allocate a default workspace name for agent: " + agentId);
    }

    private List<String> defaultNames(String agentId) {
        var slug = agentId.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 24) slug = slug.substring(0, 24);
        return List.of("default", "default-" + (slug.isEmpty() ? "agent" : slug),
                "default-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public WorkspaceRecord update(String ownerUserId, String id, String name, Integer idleSleepMinutes) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        if (name != null) rename(ownerUserId, workspace, name);
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

    private void rename(String ownerUserId, WorkspaceRecord workspace, String name) {
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

    /** explicit retry of a failed volume; idempotent for a healthy one */
    public WorkspaceRecord ensureVolume(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        try {
            volumeManager.ensure(workspace);
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
            volume = volumeManager.ensure(workspace);
        } catch (WorkspaceProvisioningException e) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, e.getMessage(), e);
        }

        var existingBinding = bindings.get(id);
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.READY && existingBinding.isPresent()) {
            // a READY row is only a cache: the controller may already have shut the runtime down
            if (runtimeExists(existingBinding.get().sandboxId)) {
                workspaces.touchLastUsed(id, ZonedDateTime.now());
                return load(ownerUserId, id);
            }
            LOGGER.info("workspace runtime is gone, provisioning a new one, workspace={}, runtime={}",
                    id, existingBinding.get().sandboxId);
        }
        if (existingBinding.isPresent()) {
            // stale pointer (server crash, controller eviction): the CR is replaceable, the PVC is not
            deleteRuntimeQuietly(existingBinding.get().sandboxId);
            bindings.delete(id);
        }
        WorkspaceQuotas.checkRunning(config, workspaces.listByOwner(ownerUserId), id);
        return startRuntime(workspace, ownerUserId, volume);
    }

    private WorkspaceRecord startRuntime(WorkspaceRecord workspace, String ownerUserId, WorkspaceVolumeRecord volume) {
        var id = workspace.id;
        // wakeAsync already claimed the next generation; only a fresh call allocates one
        var runtimeGeneration = workspace.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING
                ? workspace.runtimeGeneration
                : workspace.runtimeGeneration + 1;
        workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.PROVISIONING, runtimeGeneration, null, null);

        var runtime = runtimeSpec(workspace, ownerUserId, volume, runtimeGeneration);
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
        var generation = volumeManager.reset(workspace);
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

    private WorkspaceRuntimeSpec runtimeSpec(WorkspaceRecord workspace, String ownerUserId, WorkspaceVolumeRecord volume, long runtimeGeneration) {
        var runtime = new WorkspaceRuntimeSpec();
        runtime.workspaceId = workspace.id;
        runtime.ownerUserId = ownerUserId;
        runtime.image = workspace.image != null ? workspace.image : config.image;
        runtime.pvcName = volume.pvcName;
        runtime.runtimeGeneration = runtimeGeneration;
        runtime.profile = workspace.profile;
        runtime.maxRuntimeSeconds = WorkspaceDefaults.MAX_RUNTIME_SECONDS;
        return runtime;
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

    private boolean runtimeExists(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) return false;
        try {
            return runtimes.get(sandboxId).isPresent();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read workspace runtime {}, treating it as gone", sandboxId, e);
            return false;
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
