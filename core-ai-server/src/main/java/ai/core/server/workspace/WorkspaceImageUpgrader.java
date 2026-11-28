package ai.core.server.workspace;

import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Coordinates image and template upgrades for a workspace. Any image change must first capture a
 * cold checkpoint archive before replacing the runtime (keeping the volume intact). When the new
 * image fails to start, the checkpoint archive unlocks an explicit rollback back to the prior image.
 *
 * @author stephen
 */
final class WorkspaceImageUpgrader {
    private static final int MAX_IMAGE_LENGTH = 256;

    static String resolveTargetImage(String image, String template) {
        if (image != null && !image.isBlank()) return image.trim();
        if (template != null && !template.isBlank()) return template.trim();
        return null;
    }

    static WorkspaceRecord upgrade(WorkspaceService service, WorkspaceArchiveService archiveService,
                                   String ownerUserId, WorkspaceRecord workspace, String targetImage) {
        var access = service.archiveAccess();
        var currentImage = effectiveImage(workspace, access.config());
        var normalized = validateImage(targetImage);
        if (normalized.equals(currentImage)) {
            return workspace;
        }
        if (archiveService == null) {
            throw new WorkspaceException(WorkspaceException.UPGRADE_REQUIRES_ARCHIVE,
                    "image upgrade requires checkpoint archive, but archive service is unavailable");
        }
        var checkpoint = createCheckpoint(archiveService, ownerUserId, workspace.id);
        workspace.image = normalized;
        workspace.updatedAt = ZonedDateTime.now();
        access.stores().workspaces().update(workspace);
        access.audit().append(workspace.id, "image_upgraded", ownerUserId, Map.of(
                "fromImage", currentImage,
                "toImage", normalized,
                "archiveId", checkpoint.id));
        rebuildRuntimePreservingVolume(access, ownerUserId, workspace);
        return service.get(ownerUserId, workspace.id);
    }

    static String eligibleRollbackImage(WorkspaceEventStore events, WorkspaceRecord workspace, String archiveId) {
        if (events == null || workspace == null || archiveId == null || archiveId.isBlank()) return null;
        if (workspace.runtimeStatus != WorkspaceRuntimeStatus.ERROR) return null;
        for (var event : events.list(workspace.id, 15)) {
            if ("woken".equals(event.eventType) || "image_rollback".equals(event.eventType)) {
                return null;
            }
            if ("image_upgraded".equals(event.eventType)) {
                return matchUpgradePayload(event.payloadJson, workspace.image, archiveId);
            }
        }
        return null;
    }

    private static WorkspaceArchiveRecord createCheckpoint(WorkspaceArchiveService archiveService,
                                                           String ownerUserId, String workspaceId) {
        try {
            return archiveService.create(ownerUserId, workspaceId);
        } catch (RuntimeException e) {
            throw new WorkspaceException(WorkspaceException.UPGRADE_REQUIRES_ARCHIVE,
                    "image upgrade rejected: checkpoint archive failed (" + e.getMessage() + ")", e);
        }
    }

    private static void rebuildRuntimePreservingVolume(WorkspaceArchiveAccess access,
                                                       String ownerUserId, WorkspaceRecord workspace) {
        if (!access.runtimes().available()) {
            throw new WorkspaceException(WorkspaceException.UNSUPPORTED, "workspace runtime provider is not configured");
        }
        access.leases().releaseByWorkspace(workspace.id);
        access.runtimeLifecycle().stop(workspace);
        WorkspaceVolumeRecord volume;
        try {
            volume = access.volumeManager().ensure(workspace);
        } catch (WorkspaceProvisioningException e) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, e.getMessage(), e);
        }
        WorkspaceQuotas.checkRunning(access.config(), access.stores().workspaces().listByOwner(ownerUserId), workspace.id);
        access.runtimeStarter().start(workspace, ownerUserId, volume, false);
    }

    private static String matchUpgradePayload(String payloadJson, String currentImage, String archiveId) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            var payload = JSON.fromJSON(UpgradeEventPayload.class, payloadJson);
            if (!archiveId.equals(payload.archiveId)) return null;
            if (currentImage == null || !currentImage.equals(payload.toImage)) return null;
            return payload.fromImage != null && !payload.fromImage.isBlank() ? payload.fromImage : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String effectiveImage(WorkspaceRecord workspace, WorkspaceConfig config) {
        if (workspace.image != null && !workspace.image.isBlank()) return workspace.image.trim();
        return config.image != null ? config.image.trim() : WorkspaceDefaults.IMAGE;
    }

    private static String validateImage(String targetImage) {
        var trimmed = targetImage == null ? "" : targetImage.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_IMAGE_LENGTH || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "invalid workspace image");
        }
        return trimmed;
    }

    private WorkspaceImageUpgrader() {
    }

    public static class UpgradeEventPayload {
        @Property(name = "fromImage")
        public String fromImage;

        @Property(name = "toImage")
        public String toImage;

        @Property(name = "archiveId")
        public String archiveId;
    }
}
