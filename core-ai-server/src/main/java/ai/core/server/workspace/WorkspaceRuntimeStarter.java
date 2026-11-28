package ai.core.server.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Starts a workspace runtime, executes bootstrap.sh (once per volume) and start.sh (every wake)
 * before transitioning the workspace to READY, and records failure logs while keeping the volume.
 *
 * @author stephen
 */
class WorkspaceRuntimeStarter {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceRuntimeStarter.class);

    static boolean isInitFailure(String errorCode) {
        return WorkspaceException.BOOTSTRAP_FAILED.equals(errorCode)
                || WorkspaceException.START_FAILED.equals(errorCode);
    }

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceProvisioner provisioner;
    private final WorkspaceRuntimeManager runtimes;
    private final WorkspaceRuntimeLifecycle runtimeLifecycle;
    private final WorkspaceBootstrapRunner bootstrapRunner;
    private final WorkspaceAudit audit;
    private final WorkspaceUsageService usage;
    private final WorkspaceConfig config;
    private final Executor wakeExecutor;

    WorkspaceRuntimeStarter(Deps deps) {
        this.workspaces = deps.workspaces();
        this.bindings = deps.bindings();
        this.provisioner = deps.provisioner();
        this.runtimes = deps.runtimes();
        this.runtimeLifecycle = deps.runtimeLifecycle();
        this.bootstrapRunner = deps.bootstrapRunner();
        this.audit = deps.audit();
        this.usage = deps.usage();
        this.config = deps.config();
        this.wakeExecutor = deps.wakeExecutor();
    }

    void start(WorkspaceRecord workspace, String ownerUserId, WorkspaceVolumeRecord volume, boolean forceRerunBootstrap) {
        var id = workspace.id;
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
            runtimeLifecycle.deleteQuietly(sandboxName);
            workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.ERROR, runtimeGeneration,
                    WorkspaceException.WAKE_FAILED, e.getMessage());
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED,
                    "workspace runtime failed to start: " + e.getMessage(), e);
        }

        if (bootstrapRunner != null) {
            runInitialization(workspace, ownerUserId, sandboxName, runtimeGeneration, forceRerunBootstrap);
        }

        var now = ZonedDateTime.now();
        var binding = WorkspaceBindingRecord.create(id, sandboxName, provisioner.provider(), runtimeGeneration);
        binding.runtimeStartedAt = now;
        bindings.upsert(binding);
        workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.READY, runtimeGeneration, null, null);
        workspaces.touchWoken(id, now);
        workspaces.touchLastUsed(id, now);
        audit.append(id, "woken", ownerUserId, Map.of("sandboxId", sandboxName, "runtimeGeneration", runtimeGeneration));
        sampleUsageAsync(id);
    }

    StartedRuntime startRaw(WorkspaceRecord workspace, String ownerUserId, WorkspaceVolumeRecord volume) {
        var runtimeGeneration = workspace.runtimeGeneration + 1;
        var runtime = runtimeSpec(workspace, ownerUserId, volume, runtimeGeneration);
        var sandboxName = runtimes.name(runtime);
        try {
            runtimes.create(runtime);
            runtimes.waitReady(sandboxName, config.wakeTimeoutSeconds);
        } catch (RuntimeException e) {
            runtimeLifecycle.deleteQuietly(sandboxName);
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED,
                    "workspace runtime failed to start: " + e.getMessage(), e);
        }
        var host = runtimes.endpointHost(sandboxName).orElse(null);
        if (host == null) {
            runtimeLifecycle.deleteQuietly(sandboxName);
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED,
                    "workspace runtime endpoint unavailable: " + sandboxName);
        }
        return new StartedRuntime(sandboxName, host, runtimeGeneration);
    }

    void finalizeRestored(WorkspaceRecord workspace, String ownerUserId, StartedRuntime started) {
        var id = workspace.id;
        var now = ZonedDateTime.now();
        var binding = WorkspaceBindingRecord.create(id, started.sandboxName(), provisioner.provider(), started.runtimeGeneration());
        binding.runtimeStartedAt = now;
        bindings.upsert(binding);
        workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.READY, started.runtimeGeneration(), null, null);
        workspaces.touchWoken(id, now);
        workspaces.touchLastUsed(id, now);
        sampleUsageAsync(id);
    }

    void abortRaw(StartedRuntime started) {
        if (started != null) {
            runtimeLifecycle.deleteQuietly(started.sandboxName());
        }
    }

    private void runInitialization(WorkspaceRecord workspace, String ownerUserId, String sandboxName,
                                   long runtimeGeneration, boolean forceRerunBootstrap) {
        var id = workspace.id;
        var host = runtimes.endpointHost(sandboxName).orElse(null);
        if (host == null) {
            runtimeLifecycle.deleteQuietly(sandboxName);
            var msg = "workspace runtime endpoint unavailable for bootstrap: " + sandboxName;
            workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.ERROR, runtimeGeneration,
                    WorkspaceException.WAKE_FAILED, msg);
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED, msg);
        }
        try {
            var state = bootstrapRunner.prepare(workspace, host, forceRerunBootstrap);
            workspaces.updateBootstrapState(id, state);
        } catch (WorkspaceBootstrapRunner.BootstrapFailure e) {
            runtimeLifecycle.deleteQuietly(sandboxName);
            workspaces.updateBootstrapState(id, e.state);
            workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.ERROR, runtimeGeneration,
                    e.errorCode, e.getMessage());
            var eventType = WorkspaceException.BOOTSTRAP_FAILED.equals(e.errorCode) ? "bootstrap_failed" : "start_failed";
            audit.append(id, eventType, ownerUserId, Map.of("errorCode", e.errorCode, "message", e.getMessage()));
            throw new WorkspaceException(e.errorCode, e.getMessage(), e);
        } catch (RuntimeException e) {
            runtimeLifecycle.deleteQuietly(sandboxName);
            workspaces.updateRuntimeState(id, WorkspaceRuntimeStatus.ERROR, runtimeGeneration,
                    WorkspaceException.BOOTSTRAP_FAILED, e.getMessage());
            audit.append(id, "bootstrap_failed", ownerUserId, Map.of("errorCode", WorkspaceException.BOOTSTRAP_FAILED));
            throw new WorkspaceException(WorkspaceException.BOOTSTRAP_FAILED,
                    "workspace initialization failed: " + e.getMessage(), e);
        }
    }

    private void sampleUsageAsync(String id) {
        if (wakeExecutor == null || usage == null) return;
        try {
            wakeExecutor.execute(() -> usage.sample(id));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to schedule workspace usage sample, workspace={}", id, e);
        }
    }

    private WorkspaceRuntimeSpec runtimeSpec(WorkspaceRecord workspace, String ownerUserId,
                                             WorkspaceVolumeRecord volume, long runtimeGeneration) {
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

    record StartedRuntime(String sandboxName, String host, long runtimeGeneration) {
    }

    record Deps(WorkspaceStore workspaces, WorkspaceBindingStore bindings, WorkspaceProvisioner provisioner,
                WorkspaceRuntimeManager runtimes, WorkspaceRuntimeLifecycle runtimeLifecycle,
                WorkspaceBootstrapRunner bootstrapRunner, WorkspaceAudit audit, WorkspaceUsageService usage,
                WorkspaceConfig config, Executor wakeExecutor) {
    }
}
