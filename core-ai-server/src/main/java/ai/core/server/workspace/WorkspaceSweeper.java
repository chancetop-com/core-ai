package ai.core.server.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Periodic lifecycle: sleep runtimes whose workspace stayed idle, purge soft-deleted workspaces after
 * the retention window, and retry volumes stuck in DELETING. Every operation takes the workspace lease
 * first, so a sweep can never stop a runtime that a turn just started using.
 *
 * @author stephen
 */
class WorkspaceSweeper {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceSweeper.class);
    // Sampling probes a live runtime (k8s GET + HTTP call), so a tick samples at most this many
    // workspaces: a fleet of sleeping pods can never stretch one sweep past its job lease.
    private static final int MAX_USAGE_SAMPLES_PER_SWEEP = 10;

    private final Deps deps;
    private final WorkspaceUsageService usage;
    private volatile WorkspaceArchiveService archiveService;
    private volatile WorkspaceOrphanScanner orphanScanner = WorkspaceOrphanScanner.noop();

    WorkspaceSweeper(Deps deps, WorkspaceUsageService usage) {
        this.deps = deps;
        this.usage = usage;
    }

    void attachArchiveService(WorkspaceArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    void attachOrphanScanner(WorkspaceOrphanScanner orphanScanner) {
        this.orphanScanner = orphanScanner == null ? WorkspaceOrphanScanner.noop() : orphanScanner;
    }

    SweepResult run(WorkspaceConfig config, Predicate<String> sessionBusy) {
        var slept = sleepIdleRuntimes(config, sessionBusy);
        var purged = purgeDeletedWorkspaces(config);
        var volumes = retryDeletingVolumes();
        var archivesPurged = archiveService != null ? archiveService.purgeExpired() : 0;
        var orphans = reconcileOrphans(config);
        // Usage sampling is the only step that talks to a live runtime, so it runs last: a hung or
        // outdated pod delays the next sample, never the sleep/purge that keeps storage reclaimable.
        var sampled = sampleReadyWorkspaces(config);
        return new SweepResult(slept, purged, volumes, sampled, archivesPurged, orphans);
    }

    private WorkspaceOrphanReconciler.OrphanResult reconcileOrphans(WorkspaceConfig config) {
        var scanner = orphanScanner;
        if (scanner == null) return WorkspaceOrphanReconciler.OrphanResult.EMPTY;
        return new WorkspaceOrphanReconciler(deps.workspaces, deps.volumes, deps.bindings, scanner).reconcile(config);
    }

    private int sampleReadyWorkspaces(WorkspaceConfig config) {
        if (config.usageSampleMinutes <= 0 || usage == null) return 0;
        var ready = deps.workspaces.listActive().stream()
                .filter(w -> w.runtimeStatus == WorkspaceRuntimeStatus.READY)
                .toList();
        if (ready.isEmpty()) return 0;
        var existing = usage.readAll(ready.stream().map(w -> w.id).toList());
        var staleBefore = ZonedDateTime.now().minusMinutes(config.usageSampleMinutes);
        var due = ready.stream()
                .filter(w -> {
                    var cached = existing.get(w.id);
                    return cached == null || cached.sampledAt == null || cached.sampledAt.isBefore(staleBefore);
                })
                .limit(MAX_USAGE_SAMPLES_PER_SWEEP)
                .toList();
        var sampled = 0;
        for (var workspace : due) {
            if (usage.sample(workspace.id)) sampled++;
        }
        return sampled;
    }

    private int sleepIdleRuntimes(WorkspaceConfig config, Predicate<String> sessionBusy) {
        if (!deps.lifecycle.runtimeAvailable()) return 0;
        var now = ZonedDateTime.now();
        var slept = 0;
        for (var workspace : deps.workspaces.listActive()) {
            if (workspace.runtimeStatus != WorkspaceRuntimeStatus.READY) continue;
            var binding = deps.bindings.get(workspace.id).orElse(null);
            if (binding == null) {
                deps.workspaces.updateRuntimeState(workspace.id, WorkspaceRuntimeStatus.NONE, workspace.runtimeGeneration, null, null);
                continue;
            }
            if (WorkspaceLeaseManager.active(sessionBusy, binding.boundSessionId)) continue;
            var lease = deps.leases.current(workspace.id);
            if (lease != null && !lease.expired(now) && WorkspaceLeaseManager.active(sessionBusy, lease.sessionId)) continue;
            if (!idleEnough(workspace, binding, now, config)) continue;
            if (sleep(workspace, binding, now, sessionBusy)) slept++;
        }
        return slept;
    }

    private boolean sleep(WorkspaceRecord workspace, WorkspaceBindingRecord binding, ZonedDateTime now,
                          Predicate<String> sessionBusy) {
        var maintenance = deps.leases.tryAcquireMaintenance(workspace.id, binding.runtimeGeneration, sessionBusy);
        if (maintenance == null) return false;
        try {
            var current = deps.workspaces.get(workspace.id).orElse(null);
            if (current == null || current.deleted() || current.runtimeStatus != WorkspaceRuntimeStatus.READY) return false;
            deps.lifecycle.stop(current);
            deps.audit.append(workspace.id, "auto_slept", "sleeper", Map.of(
                    "idleMinutes", idleMinutes(current, now),
                    "runtimeGeneration", binding.runtimeGeneration));
            LOGGER.info("workspace slept after idle, workspace={}, idleMinutes={}", workspace.id, idleMinutes(current, now));
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to sleep idle workspace, retry next sweep, workspace={}", workspace.id, e);
            return false;
        } finally {
            deps.leases.release(workspace.id, maintenance.leaseToken);
        }
    }

    private boolean idleEnough(WorkspaceRecord workspace, WorkspaceBindingRecord binding, ZonedDateTime now,
                               WorkspaceConfig config) {
        var startedAt = binding.runtimeStartedAt;
        if (startedAt != null) {
            var ageSeconds = Duration.between(startedAt, now).getSeconds();
            if (ageSeconds >= WorkspaceDefaults.MAX_RUNTIME_SECONDS) return true;   // never keep a runtime for days
            if (ageSeconds < config.minRuntimeSeconds) return false;
        }
        return idleMinutes(workspace, now) >= Math.max(1, workspace.idleSleepMinutes);
    }

    private long idleMinutes(WorkspaceRecord workspace, ZonedDateTime now) {
        var lastUsed = workspace.lastUsedAt != null ? workspace.lastUsedAt
                : workspace.updatedAt != null ? workspace.updatedAt : workspace.createdAt;
        return lastUsed == null ? Long.MAX_VALUE : Math.max(0, Duration.between(lastUsed, now).toMinutes());
    }

    private int purgeDeletedWorkspaces(WorkspaceConfig config) {
        var cutoff = ZonedDateTime.now().minusDays(Math.max(1, config.purgeAfterDays));
        var purged = 0;
        for (var workspace : deps.workspaces.listDeletedBefore(cutoff)) {
            try {
                if (purge(workspace)) purged++;
            } catch (RuntimeException e) {
                LOGGER.warn("failed to purge deleted workspace, retry next sweep, workspace={}", workspace.id, e);
            }
        }
        return purged;
    }

    /**
     * Volume first, row last: marking the volumes DELETING fences a concurrent restore (it then sees
     * no active volume), and a failed PVC deletion leaves a retryable row instead of an invisible PVC.
     */
    private boolean purge(WorkspaceRecord workspace) {
        var current = deps.workspaces.get(workspace.id).orElse(null);
        if (current == null || !current.deleted()) return false;
        var volumes = deps.volumes.list(workspace.id);
        var previousStatuses = new LinkedHashMap<WorkspaceVolumeRecord, WorkspaceVolumeStatus>();
        for (var volume : volumes) {
            if (volume.status == WorkspaceVolumeStatus.DELETED) continue;
            previousStatuses.put(volume, volume.status);
            deps.volumes.markDeleting(volume);
        }
        // a restore that started before the volumes were fenced must win: undo the DELETING marks
        var fenced = deps.workspaces.get(workspace.id).orElse(null);
        if (fenced == null || !fenced.deleted()) {
            previousStatuses.forEach(deps.volumes::restoreStatus);
            return false;
        }
        deps.leases.releaseByWorkspace(workspace.id);
        deps.lifecycle.stop(fenced);
        for (var volume : volumes) {
            if (volume.status == WorkspaceVolumeStatus.DELETED) continue;
            if (!deps.volumes.deleteNow(volume)) return false;
        }
        deps.bindings.delete(workspace.id);
        deps.audit.delete(workspace.id);
        if (usage != null) usage.delete(workspace.id);
        if (!deps.workspaces.purge(workspace.id)) return false;
        LOGGER.info("workspace purged, workspace={}, volumes={}", workspace.id, volumes.size());
        return true;
    }

    private int retryDeletingVolumes() {
        var cutoff = ZonedDateTime.now().minusMinutes(10);
        var deleted = 0;
        for (var volume : deps.volumes.listByStatus(WorkspaceVolumeStatus.DELETING)) {
            if (volume.deletedAt != null && volume.deletedAt.isAfter(cutoff)) continue;
            if (deps.volumes.deleteNow(volume)) deleted++;
        }
        return deleted;
    }

    record Deps(WorkspaceStore workspaces, WorkspaceBindingStore bindings, WorkspaceLeaseManager leases,
                WorkspaceVolumeManager volumes, WorkspaceRuntimeLifecycle lifecycle, WorkspaceAudit audit) {
    }

    record SweepResult(int slept, int purged, int volumesDeleted, int usageSampled, int archivesPurged,
                       WorkspaceOrphanReconciler.OrphanResult orphans) {
        static final SweepResult EMPTY = new SweepResult(0, 0, 0, 0, 0, WorkspaceOrphanReconciler.OrphanResult.EMPTY);

        int orphanPvcsDeleted() {
            return orphans.pvcsDeleted();
        }

        int orphanSandboxesDeleted() {
            return orphans.sandboxesDeleted();
        }
    }
}
