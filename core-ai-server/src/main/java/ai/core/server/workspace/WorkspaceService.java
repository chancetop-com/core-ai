package ai.core.server.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Workspace lifecycle: the durable volume is provisioned once and survives every runtime, while
 * the runtime (Sandbox CR) is created on wake and deleted on sleep. Volume faults mark
 * {@code volume_status=ERROR} and stop there; runtime faults mark {@code runtime_status=ERROR} and
 * keep the already-provisioned volume, so a scheduling failure never looks like data loss.
 * <p>
 * A turn owns the workspace through a persisted lease, so two sessions never write the same volume
 * at once. The lease of an active turn is renewed while that turn runs; a lease whose holder is no
 * longer running a turn is stealable, and a lease whose pod died expires by TTL.
 *
 * @author stephen
 */
public class WorkspaceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceEventStore events;
    private final WorkspaceVolumeManager volumeManager;
    private final WorkspaceRuntimeManager runtimes;
    private final WorkspaceRuntimeLifecycle runtimeLifecycle;
    private final WorkspaceRuntimeStarter runtimeStarter;
    private final WorkspaceLeaseManager leases;
    private final WorkspaceAudit audit;
    private final WorkspaceCreator creator;
    private final WorkspaceUsageService usage;
    private final WorkspaceSweeper sweeper;
    private final WorkspaceConfig config;
    private final Executor wakeExecutor;
    /** Answers "is this session executing a turn"; assigned by RuntimeGovernanceModule. */
    public volatile Predicate<String> sessionBusy;

    public WorkspaceService(WorkspaceStores stores, WorkspaceProvisioner provisioner,
                            WorkspaceRuntimeManager runtimes, WorkspaceConfig config, Executor wakeExecutor) {
        this(stores, provisioner, runtimes,
                new WorkspaceUsageService(stores.workspaces(), stores.bindings(), stores.usage(), runtimes),
                config, wakeExecutor);
    }

    public WorkspaceService(WorkspaceStores stores, WorkspaceProvisioner provisioner,
                            WorkspaceRuntimeManager runtimes, WorkspaceUsageService usage,
                            WorkspaceConfig config, Executor wakeExecutor) {
        this(stores, new RuntimeDeps(provisioner, runtimes, usage,
                runtimes != null && runtimes.available() ? new WorkspaceBootstrapRunner(config) : null),
                config, wakeExecutor);
    }

    WorkspaceService(WorkspaceStores stores, RuntimeDeps deps, WorkspaceConfig config, Executor wakeExecutor) {
        this.workspaces = stores.workspaces();
        this.bindings = stores.bindings();
        this.events = stores.events();
        this.volumeManager = new WorkspaceVolumeManager(stores.volumes(), stores.workspaces(), deps.provisioner());
        this.runtimes = deps.runtimes();
        this.runtimeLifecycle = new WorkspaceRuntimeLifecycle(stores.workspaces(), stores.bindings(), deps.runtimes());
        this.leases = new WorkspaceLeaseManager(stores.leases(), config);
        this.audit = new WorkspaceAudit(stores.events());
        this.creator = new WorkspaceCreator(stores.workspaces(), volumeManager, audit, config);
        this.usage = deps.usage();
        this.runtimeStarter = new WorkspaceRuntimeStarter(new WorkspaceRuntimeStarter.Deps(
                stores.workspaces(), stores.bindings(), deps.provisioner(), deps.runtimes(), runtimeLifecycle,
                deps.bootstrapRunner(), audit, deps.usage(), config, wakeExecutor));
        this.sweeper = new WorkspaceSweeper(new WorkspaceSweeper.Deps(stores.workspaces(), stores.bindings(),
                leases, volumeManager, runtimeLifecycle, audit), this.usage);
        this.config = config;
        this.wakeExecutor = wakeExecutor;
    }

    public WorkspaceRecord create(String ownerUserId, String name, String agentId, Integer sizeGb, String profileId) {
        return create(ownerUserId, name, agentId, sizeGb, profileId, null);
    }

    public WorkspaceRecord create(String ownerUserId, String name, String agentId, Integer sizeGb, String profileId,
                                  Integer idleSleepMinutes) {
        return create(ownerUserId, new WorkspaceCreateSpec(name, agentId, sizeGb, profileId, idleSleepMinutes, null, null));
    }

    public WorkspaceRecord create(String ownerUserId, WorkspaceCreateSpec spec) {
        requireEnabled();
        return load(ownerUserId, creator.create(ownerUserId, spec).id);
    }

    public WorkspaceRecord get(String ownerUserId, String id) {
        requireEnabled();
        return load(ownerUserId, id);
    }

    public List<WorkspaceRecord> list(String ownerUserId) {
        return list(ownerUserId, false);
    }

    public List<WorkspaceRecord> list(String ownerUserId, boolean includeDeleted) {
        requireEnabled();
        return workspaces.listByOwner(ownerUserId, includeDeleted);
    }

    public List<WorkspaceEventRecord> events(String ownerUserId, String id, int limit) {
        requireEnabled();
        load(ownerUserId, id);
        return events.list(id, Math.max(1, Math.min(limit, 200)));
    }

    public Optional<WorkspaceUsageRecord> usage(String ownerUserId, String id) {
        requireEnabled();
        load(ownerUserId, id);
        return usage.read(id);
    }

    public Map<String, WorkspaceUsageRecord> usageByWorkspaces(List<String> workspaceIds) {
        requireEnabled();
        return usage.readAll(workspaceIds);
    }

    public boolean enabled() {
        return config.enabled;
    }

    /** false when the deployment has no sandbox runtime backend, so a workspace cannot run here */
    public boolean runtimeAvailable() {
        return runtimes.available();
    }

    public WorkspaceRecord resolveDefault(String ownerUserId, String agentId) {
        return resolveDefault(ownerUserId, agentId, null, null);
    }

    public WorkspaceRecord resolveDefault(String ownerUserId, String agentId, Integer sizeGb, String profileId) {
        requireEnabled();
        return load(ownerUserId, creator.resolveDefault(ownerUserId, agentId, sizeGb, profileId).id);
    }

    public WorkspaceRecord update(String ownerUserId, String id, String name, Integer idleSleepMinutes) {
        return update(ownerUserId, id, name, idleSleepMinutes, null, null);
    }

    public WorkspaceRecord update(String ownerUserId, String id, String name, Integer idleSleepMinutes,
                                  String bootstrapScript, String startScript) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        if (name != null) creator.rename(ownerUserId, workspace, name);
        if (idleSleepMinutes != null) {
            workspace.idleSleepMinutes = creator.validatedIdleSleep(idleSleepMinutes);
        }
        if (bootstrapScript != null) workspace.bootstrapScript = bootstrapScript;
        if (startScript != null) workspace.startScript = startScript;
        workspace.updatedAt = ZonedDateTime.now();
        workspaces.update(workspace);
        audit.append(id, "updated", ownerUserId, Map.of());
        return load(ownerUserId, id);
    }

    /** explicit retry of a failed volume; idempotent for a healthy one */
    public WorkspaceRecord ensureVolume(String ownerUserId, String id) {
        requireEnabled();
        ensureVolumeFor(loadActive(ownerUserId, id));
        return load(ownerUserId, id);
    }

    public WorkspaceRecord wakeAsync(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        ensureRuntimeSupported();
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.READY
                || workspace.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
            workspaces.touchLastUsed(id, ZonedDateTime.now());
            return load(ownerUserId, id);
        }
        return scheduleAsyncStart(ownerUserId, workspace, () -> wake(ownerUserId, id), "wake");
    }

    public WorkspaceRecord wake(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        ensureRuntimeSupported();
        var volume = ensureVolumeFor(workspace);
        var existingBinding = bindings.get(id);
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.READY && existingBinding.isPresent()) {
            if (runtimeLifecycle.exists(existingBinding.get().sandboxId)) {
                workspaces.touchLastUsed(id, ZonedDateTime.now());
                return load(ownerUserId, id);
            }
            LOGGER.info("workspace runtime is gone, provisioning a new one, workspace={}, runtime={}",
                    id, existingBinding.get().sandboxId);
        }
        if (existingBinding.isPresent()) {
            runtimeLifecycle.deleteQuietly(existingBinding.get().sandboxId);
            bindings.delete(id);
        }
        WorkspaceQuotas.checkRunning(config, workspaces.listByOwner(ownerUserId), id);
        runtimeStarter.start(workspace, ownerUserId, volume, false);
        return load(ownerUserId, id);
    }

    public WorkspaceRecord retryAsync(String ownerUserId, String id, boolean rerunBootstrap) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        ensureRuntimeSupported();
        refuseWhenTurnRunning(workspace);
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
            return load(ownerUserId, id);
        }
        return scheduleAsyncStart(ownerUserId, workspace, () -> retry(ownerUserId, id, rerunBootstrap), "retry");
    }

    public WorkspaceRecord retry(String ownerUserId, String id, boolean rerunBootstrap) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        ensureRuntimeSupported();
        refuseWhenTurnRunning(workspace);
        var volume = ensureVolumeFor(workspace);
        leases.releaseByWorkspace(id);
        bindings.get(id).ifPresent(binding -> {
            runtimeLifecycle.deleteQuietly(binding.sandboxId);
            bindings.delete(id);
        });
        WorkspaceQuotas.checkRunning(config, workspaces.listByOwner(ownerUserId), id);
        runtimeStarter.start(workspace, ownerUserId, volume, rerunBootstrap);
        return load(ownerUserId, id);
    }

    private WorkspaceRecord scheduleAsyncStart(String ownerUserId, WorkspaceRecord workspace, Runnable action, String label) {
        workspaces.updateRuntimeState(workspace.id, WorkspaceRuntimeStatus.PROVISIONING,
                workspace.runtimeGeneration + 1, null, null);
        wakeExecutor.execute(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                LOGGER.warn("background workspace {} failed, workspace={}", label, workspace.id, e);
            }
        });
        return load(ownerUserId, workspace.id);
    }

    private WorkspaceVolumeRecord ensureVolumeFor(WorkspaceRecord workspace) {
        try {
            return volumeManager.ensure(workspace);
        } catch (WorkspaceProvisioningException e) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, e.getMessage(), e);
        }
    }

    /** Start of a turn's tool execution: wake/attach, then take the workspace lease (waits, then WORKSPACE_BUSY). */
    public WorkspaceTurn beginTurn(String ownerUserId, String id, String sessionId, boolean verifyRuntime) {
        requireEnabled();
        ensureRuntimeSupported();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, config.leaseWaitSeconds));
        while (true) {
            var binding = readyBinding(ownerUserId, id, verifyRuntime);
            var lease = binding != null ? leases.acquireTurn(id, sessionId, binding.runtimeGeneration, sessionBusy) : null;
            if (binding != null && lease != null) {
                var rebound = !sessionId.equals(binding.boundSessionId);
                if (rebound) {
                    bindings.rebindSession(id, binding.sandboxId, binding.runtimeGeneration, sessionId, ZonedDateTime.now());
                }
                workspaces.touchLastUsed(id, ZonedDateTime.now());
                return new WorkspaceTurn(binding, lease.leaseToken, rebound);
            }
            if (System.nanoTime() >= deadline) throw busy();
            WorkspaceLeaseManager.sleepUntilRetry();
        }
    }

    private WorkspaceBindingRecord readyBinding(String ownerUserId, String id, boolean verifyRuntime) {
        var workspace = loadActive(ownerUserId, id);
        refuseIfInitFailed(workspace);
        var binding = bindings.get(id).orElse(null);
        var usable = workspace.runtimeStatus == WorkspaceRuntimeStatus.READY && binding != null
                && (!verifyRuntime || runtimeLifecycle.exists(binding.sandboxId));
        if (usable) return binding;
        wake(ownerUserId, id);
        var refreshed = loadActive(ownerUserId, id);
        if (refreshed.runtimeStatus != WorkspaceRuntimeStatus.READY) return null;
        return bindings.get(id).orElse(null);
    }

    private void refuseIfInitFailed(WorkspaceRecord workspace) {
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.ERROR
                && WorkspaceRuntimeStarter.isInitFailure(workspace.errorCode)) {
            var detail = workspace.errorMessage != null ? workspace.errorMessage : "workspace bootstrap/start failed";
            throw new WorkspaceException(workspace.errorCode,
                    "workspace is not ready (" + workspace.errorCode + "): " + detail
                            + "; fix the script and retry bootstrap/start");
        }
    }

    private WorkspaceException busy() {
        return new WorkspaceException(WorkspaceException.BUSY, "workspace is used by another session");
    }

    /** Runtime access for operations that are not an agent turn (MCP start, status): no lease. */
    public WorkspaceBindingRecord ensureRuntime(String ownerUserId, String id) {
        requireEnabled();
        ensureRuntimeSupported();
        var workspace = loadActive(ownerUserId, id);
        refuseIfInitFailed(workspace);
        var binding = bindings.get(id).orElse(null);
        if (workspace.runtimeStatus == WorkspaceRuntimeStatus.READY && binding != null
                && runtimeLifecycle.exists(binding.sandboxId)) {
            workspaces.touchLastUsed(id, ZonedDateTime.now());
            return binding;
        }
        wake(ownerUserId, id);
        return bindings.get(id)
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.WAKING, "workspace runtime is not ready"));
    }

    public WorkspaceLeaseHeartbeat heartbeat(String workspaceId, String leaseToken) {
        return leases.heartbeat(workspaceId, leaseToken, sessionBusy);
    }

    public void releaseLease(String workspaceId, String leaseToken) {
        leases.release(workspaceId, leaseToken);
    }

    public void sleep(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        refuseWhenTurnRunning(workspace);
        leases.releaseByWorkspace(id);
        runtimeLifecycle.stop(workspace);
        audit.append(id, "slept", ownerUserId, Map.of());
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
        refuseWhenTurnRunning(workspace);
        leases.releaseByWorkspace(id);
        runtimeLifecycle.stop(workspace);
        var generation = volumeManager.reset(workspace);
        workspaces.updateBootstrapState(id, new WorkspaceBootstrapState(null, null, null, null, false));
        usage.delete(id);
        audit.append(id, "reset", ownerUserId, Map.of("volumeGeneration", generation));
        return load(ownerUserId, id);
    }

    public void delete(String ownerUserId, String id) {
        requireEnabled();
        var workspace = loadActive(ownerUserId, id);
        refuseWhenTurnRunning(workspace);
        leases.releaseByWorkspace(id);
        runtimeLifecycle.stop(workspace);
        if (!workspaces.softDelete(id, ZonedDateTime.now())) {
            throw new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace not found: " + id);
        }
        audit.append(id, "deleted", ownerUserId, Map.of());
    }

    public WorkspaceRecord restore(String ownerUserId, String id) {
        requireEnabled();
        var workspace = workspaces.get(id)
                .filter(w -> w.ownerUserId.equals(ownerUserId))
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace not found: " + id));
        if (!workspace.deleted()) return workspace;
        if (volumeManager.active(id) == null) {
            throw new WorkspaceException(WorkspaceException.DELETED, "workspace volume has been purged");
        }
        try {
            if (!workspaces.restore(id)) {
                throw new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace not found: " + id);
            }
        } catch (WorkspaceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkspaceException(WorkspaceException.NAME_CONFLICT, "workspace name is already in use", e);
        }
        audit.append(id, "restored", ownerUserId, Map.of());
        return load(ownerUserId, id);
    }

    private void refuseWhenTurnRunning(WorkspaceRecord workspace) {
        var binding = bindings.get(workspace.id).orElse(null);
        if (binding != null && WorkspaceLeaseManager.active(sessionBusy, binding.boundSessionId)) {
            throw new WorkspaceException(WorkspaceException.BUSY, "workspace is executing a turn");
        }
    }

    public void detach(String id, String sessionId) {
        bindings.clearSessionIfBound(id, sessionId, ZonedDateTime.now());
    }

    WorkspaceSweeper.SweepResult runLifecycleSweep() {
        if (!config.enabled || workspaces == null) return new WorkspaceSweeper.SweepResult(0, 0, 0, 0);
        return sweeper.run(config, sessionBusy);
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

    record RuntimeDeps(WorkspaceProvisioner provisioner, WorkspaceRuntimeManager runtimes,
                       WorkspaceUsageService usage, WorkspaceBootstrapRunner bootstrapRunner) {
    }

    /** One turn's claim on the workspace: the runtime it may write to and the lease that proves it. */
    public record WorkspaceTurn(WorkspaceBindingRecord binding, String leaseToken, boolean rebound) {
    }
}
