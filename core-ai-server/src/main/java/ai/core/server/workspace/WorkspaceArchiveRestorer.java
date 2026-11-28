package ai.core.server.workspace;

import ai.core.sandbox.SandboxConstants;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Executes workspace archive restores for new-workspace recovery, in-place disaster recovery, and
 * image-upgrade rollback while preserving healthy volumes and keeping original volume state intact
 * when restore fails midway.
 *
 * @author stephen
 */
class WorkspaceArchiveRestorer {
    private static final int MAX_FAILURE_REASON_CHARS = 512;

    private static String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) return "restore failed";
        if (reason.length() <= MAX_FAILURE_REASON_CHARS) return reason;
        return reason.substring(0, MAX_FAILURE_REASON_CHARS);
    }

    private final WorkspaceService workspaceService;
    private final WorkspaceArchiveClient client;

    WorkspaceArchiveRestorer(WorkspaceService workspaceService, WorkspaceArchiveClient client) {
        this.workspaceService = workspaceService;
        this.client = client;
    }

    WorkspaceRecord restoreToNewWorkspace(String ownerUserId, WorkspaceRecord source,
                                          WorkspaceArchiveRecord archive, String newName, Path archiveFile) {
        var access = workspaceService.archiveAccess();
        var target = access.creator().createForRestore(ownerUserId, source, newName, archive.image);
        var volume = access.volumeManager().active(target.id);
        if (volume == null) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE,
                    "new workspace volume is unavailable: " + target.id);
        }
        WorkspaceRuntimeStarter.StartedRuntime started = null;
        try {
            started = access.runtimeStarter().startRaw(target, ownerUserId, volume);
            // the target volume was provisioned for this restore and always carries scaffold files (AGENTS.md, .core-ai/NOTES.md),
            // which the runtime counts as content, so restoring into it must be allowed to overwrite
            var options = new WorkspaceArchiveClient.RestoreOptions(archive.volumeGeneration, archive.workspaceId, true);
            client.restoreFromFile(started.host(), SandboxConstants.RUNTIME_PORT, archiveFile, options);
            access.runtimeStarter().finalizeRestored(target, ownerUserId, started);
            access.audit().append(target.id, "restored_from_archive", ownerUserId, Map.of(
                    "sourceWorkspaceId", source.id, "archiveId", archive.id, "volumeGeneration", archive.volumeGeneration));
            access.audit().append(source.id, "archive_restored", ownerUserId, Map.of(
                    "targetWorkspaceId", target.id, "archiveId", archive.id));
            return workspaceService.get(ownerUserId, target.id);
        } catch (WorkspaceException e) {
            failNewWorkspaceRestore(access, target, started, e.errorCode, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            failNewWorkspaceRestore(access, target, started, WorkspaceException.ARCHIVE_FAILED, e.getMessage());
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "failed to restore archive: " + e.getMessage(), e);
        }
    }

    WorkspaceRecord restoreInPlace(String ownerUserId, WorkspaceRecord workspace,
                                   WorkspaceArchiveRecord archive, boolean allowOverwrite, Path archiveFile) {
        var access = workspaceService.archiveAccess();
        var activeVolume = access.volumeManager().active(workspace.id);
        var volumeHealthy = isVolumeHealthy(access, workspace, activeVolume);
        var rollbackImage = allowOverwrite
                ? WorkspaceImageUpgrader.eligibleRollbackImage(access.stores().events(), workspace, archive.id)
                : null;
        if (volumeHealthy && rollbackImage == null) {
            throw new WorkspaceException(WorkspaceException.RESTORE_TARGET_EXISTS,
                    "workspace already has a healthy volume; specify new_name to restore into a new workspace");
        }
        access.leases().releaseByWorkspace(workspace.id);
        access.runtimeLifecycle().stop(workspace);
        if (rollbackImage != null) {
            return executeUpgradeRollback(ownerUserId, workspace, activeVolume, archive, rollbackImage, archiveFile);
        }
        return executeDisasterRecovery(ownerUserId, workspace, activeVolume, archive, archiveFile);
    }

    private WorkspaceRecord executeDisasterRecovery(String ownerUserId, WorkspaceRecord workspace,
                                                    WorkspaceVolumeRecord previousActive,
                                                    WorkspaceArchiveRecord archive, Path archiveFile) {
        var access = workspaceService.archiveAccess();
        var previousStatus = previousActive != null ? previousActive.status : null;
        var newVolume = access.volumeManager().provisionForRecovery(workspace, previousActive, previousStatus);
        WorkspaceRuntimeStarter.StartedRuntime started = null;
        try {
            started = access.runtimeStarter().startRaw(workspace, ownerUserId, newVolume);
            var options = new WorkspaceArchiveClient.RestoreOptions(archive.volumeGeneration, archive.workspaceId, true);
            client.restoreFromFile(started.host(), SandboxConstants.RUNTIME_PORT, archiveFile, options);
            access.volumeManager().commitRecoveryVolume(workspace, newVolume, previousActive);
            access.runtimeStarter().finalizeRestored(workspace, ownerUserId, started);
            access.audit().append(workspace.id, "restored_from_archive", ownerUserId, Map.of(
                    "archiveId", archive.id,
                    "sourceGeneration", archive.volumeGeneration,
                    "volumeGeneration", newVolume.volumeGeneration));
            return workspaceService.get(ownerUserId, workspace.id);
        } catch (WorkspaceException e) {
            access.runtimeStarter().abortRaw(started);
            access.volumeManager().rollbackRecoveryVolume(workspace, newVolume, previousActive, previousStatus);
            throw e;
        } catch (RuntimeException e) {
            access.runtimeStarter().abortRaw(started);
            access.volumeManager().rollbackRecoveryVolume(workspace, newVolume, previousActive, previousStatus);
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "failed to restore archive in-place: " + e.getMessage(), e);
        }
    }

    private WorkspaceRecord executeUpgradeRollback(String ownerUserId, WorkspaceRecord workspace,
                                                   WorkspaceVolumeRecord activeVolume, WorkspaceArchiveRecord archive,
                                                   String fromImage, Path archiveFile) {
        var access = workspaceService.archiveAccess();
        if (activeVolume == null) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, "workspace volume missing for rollback");
        }
        var originalImage = workspace.image;
        workspace.image = fromImage;
        WorkspaceRuntimeStarter.StartedRuntime started = null;
        try {
            started = access.runtimeStarter().startRaw(workspace, ownerUserId, activeVolume);
            var options = new WorkspaceArchiveClient.RestoreOptions(archive.volumeGeneration, archive.workspaceId, true);
            client.restoreFromFile(started.host(), SandboxConstants.RUNTIME_PORT, archiveFile, options);
            workspace.updatedAt = ZonedDateTime.now();
            access.stores().workspaces().update(workspace);
            access.runtimeStarter().finalizeRestored(workspace, ownerUserId, started);
            access.audit().append(workspace.id, "image_rollback", ownerUserId, Map.of(
                    "archiveId", archive.id,
                    "fromImage", originalImage == null ? "" : originalImage,
                    "toImage", fromImage));
            return workspaceService.get(ownerUserId, workspace.id);
        } catch (WorkspaceException e) {
            workspace.image = originalImage;
            access.runtimeStarter().abortRaw(started);
            throw e;
        } catch (RuntimeException e) {
            workspace.image = originalImage;
            access.runtimeStarter().abortRaw(started);
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "failed to rollback workspace upgrade: " + e.getMessage(), e);
        }
    }

    private void failNewWorkspaceRestore(WorkspaceArchiveAccess access, WorkspaceRecord target,
                                         WorkspaceRuntimeStarter.StartedRuntime started, String code, String message) {
        access.runtimeStarter().abortRaw(started);
        var generation = started != null ? started.runtimeGeneration() : target.runtimeGeneration + 1;
        access.stores().workspaces().updateRuntimeState(target.id, WorkspaceRuntimeStatus.ERROR,
                generation, code, truncateReason(message));
    }

    private boolean isVolumeHealthy(WorkspaceArchiveAccess access, WorkspaceRecord workspace,
                                    WorkspaceVolumeRecord activeVolume) {
        if (workspace.volumeStatus != WorkspaceVolumeStatus.READY || activeVolume == null
                || activeVolume.status != WorkspaceVolumeStatus.READY) {
            return false;
        }
        return access.volumeManager().volumeExists(activeVolume);
    }
}
