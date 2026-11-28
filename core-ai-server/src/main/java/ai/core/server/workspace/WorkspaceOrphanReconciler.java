package ai.core.server.workspace;

import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.kubernetes.PvcInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.OptionalLong;

/**
 * Reconciles leaked workspace PVCs and Sandbox CRs against PostgreSQL state. Data safety takes
 * priority: objects within the grace period and PVCs whose workspace exists without a matching
 * volume row are never deleted. Each sweep caps deletions per resource kind to bound blast radius.
 *
 * @author stephen
 */
class WorkspaceOrphanReconciler {
    static final int MAX_DELETES_PER_SWEEP = 20;
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceOrphanReconciler.class);
    private static final String COMPONENT_LABEL = "core-ai/component";
    private static final String COMPONENT_VALUE = "workspace";
    private static final String WORKSPACE_ID_LABEL = "core-ai/workspace-id";
    private static final String VOLUME_GENERATION_LABEL = "core-ai/volume-generation";

    private static OptionalLong parseLong(String value) {
        if (value == null || value.isBlank()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private final WorkspaceStore workspaces;
    private final WorkspaceVolumeManager volumes;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceOrphanScanner scanner;

    WorkspaceOrphanReconciler(WorkspaceStore workspaces, WorkspaceVolumeManager volumes,
                              WorkspaceBindingStore bindings, WorkspaceOrphanScanner scanner) {
        this.workspaces = workspaces;
        this.volumes = volumes;
        this.bindings = bindings;
        this.scanner = scanner;
    }

    OrphanResult reconcile(WorkspaceConfig config) {
        if (scanner == null) return OrphanResult.EMPTY;
        var graceMinutes = Math.max(1, config.orphanGraceMinutes);
        var cutoff = Instant.now().minus(Duration.ofMinutes(graceMinutes));
        var pvcsDeleted = reconcilePvcs(cutoff);
        var sandboxesDeleted = reconcileSandboxes(cutoff);
        return new OrphanResult(pvcsDeleted, sandboxesDeleted);
    }

    private int reconcilePvcs(Instant cutoff) {
        List<PvcInfo> pvcs;
        try {
            pvcs = scanner.listWorkspacePvcs();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to list workspace pvcs for orphan reconciliation", e);
            return 0;
        }
        if (pvcs == null || pvcs.isEmpty()) return 0;
        var deleted = 0;
        for (var pvc : pvcs) {
            if (deleted >= MAX_DELETES_PER_SWEEP) break;
            if (reconcilePvc(pvc, cutoff)) deleted++;
        }
        return deleted;
    }

    private boolean reconcilePvc(PvcInfo pvc, Instant cutoff) {
        if (pvc == null || pvc.metadata == null || pvc.metadata.name == null || pvc.metadata.name.isBlank()) {
            return false;
        }
        var labels = pvc.metadata.labels;
        if (labels == null || !COMPONENT_VALUE.equals(labels.get(COMPONENT_LABEL))) return false;
        var pvcName = pvc.metadata.name;
        var workspaceId = labels.get(WORKSPACE_ID_LABEL);
        if (workspaceId == null || workspaceId.isBlank()) {
            LOGGER.warn("skipping workspace pvc without workspace-id label, pvc={}", pvcName);
            return false;
        }
        if (!pastGracePeriod(pvc.metadata.creationTimestamp, cutoff, "pvc", pvcName)) return false;
        return deletePvcIfOrphan(pvcName, workspaceId, labels.get(VOLUME_GENERATION_LABEL));
    }

    private boolean deletePvcIfOrphan(String pvcName, String workspaceId, String generationLabel) {
        try {
            var reason = pvcOrphanReason(pvcName, workspaceId, generationLabel);
            if (reason == null) return false;
            scanner.deletePvc(pvcName);
            LOGGER.info("deleted orphan workspace pvc, pvc={}, workspace={}, reason={}", pvcName, workspaceId, reason);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to reconcile orphan workspace pvc, pvc={}, workspace={}", pvcName, workspaceId, e);
            return false;
        }
    }

    private String pvcOrphanReason(String pvcName, String workspaceId, String generationLabel) {
        var workspace = workspaces.get(workspaceId).orElse(null);
        if (workspace == null) return "workspace missing";
        var volume = findMatchingVolume(workspaceId, pvcName, generationLabel);
        if (volume == null) {
            LOGGER.warn("workspace exists but volume row is missing for pvc, keeping for manual inspection, workspace={}, pvc={}",
                    workspaceId, pvcName);
            return null;
        }
        if (volume.status == WorkspaceVolumeStatus.DELETED) return "volume row deleted";
        return null;
    }

    private WorkspaceVolumeRecord findMatchingVolume(String workspaceId, String pvcName, String generationLabel) {
        var rows = volumes.list(workspaceId);
        for (var row : rows) {
            if (pvcName.equals(row.pvcName)) return row;
        }
        var generation = parseLong(generationLabel);
        if (generation.isEmpty()) return null;
        var expected = generation.getAsLong();
        for (var row : rows) {
            if (row.volumeGeneration == expected) return row;
        }
        return null;
    }

    private int reconcileSandboxes(Instant cutoff) {
        List<AgentSandboxClient.SandboxCR> sandboxes;
        try {
            sandboxes = scanner.listWorkspaceSandboxes();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to list workspace sandboxes for orphan reconciliation", e);
            return 0;
        }
        if (sandboxes == null || sandboxes.isEmpty()) return 0;
        var deleted = 0;
        for (var cr : sandboxes) {
            if (deleted >= MAX_DELETES_PER_SWEEP) break;
            if (reconcileSandbox(cr, cutoff)) deleted++;
        }
        return deleted;
    }

    private boolean reconcileSandbox(AgentSandboxClient.SandboxCR cr, Instant cutoff) {
        if (cr == null || cr.metadata == null || cr.metadata.name == null || cr.metadata.name.isBlank()) {
            return false;
        }
        var labels = cr.metadata.labels;
        if (labels == null || !COMPONENT_VALUE.equals(labels.get(COMPONENT_LABEL))) return false;
        var sandboxName = cr.metadata.name;
        var workspaceId = labels.get(WORKSPACE_ID_LABEL);
        if (workspaceId == null || workspaceId.isBlank()) {
            LOGGER.warn("skipping workspace sandbox without workspace-id label, sandbox={}", sandboxName);
            return false;
        }
        if (!pastGracePeriod(cr.metadata.creationTimestamp, cutoff, "sandbox", sandboxName)) return false;
        return deleteSandboxIfOrphan(sandboxName, workspaceId);
    }

    private boolean deleteSandboxIfOrphan(String sandboxName, String workspaceId) {
        try {
            var reason = sandboxOrphanReason(sandboxName, workspaceId);
            if (reason == null) return false;
            scanner.deleteSandbox(sandboxName);
            LOGGER.info("deleted orphan workspace sandbox, sandbox={}, workspace={}, reason={}",
                    sandboxName, workspaceId, reason);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to reconcile orphan workspace sandbox, sandbox={}, workspace={}",
                    sandboxName, workspaceId, e);
            return false;
        }
    }

    private String sandboxOrphanReason(String sandboxName, String workspaceId) {
        var workspace = workspaces.get(workspaceId).orElse(null);
        if (workspace == null) return "workspace missing";
        var binding = bindings.get(workspaceId).orElse(null);
        if (binding != null && sandboxName.equals(binding.sandboxId)) return null;
        return "superseded sandbox";
    }

    private boolean pastGracePeriod(String creationTimestamp, Instant cutoff, String kind, String name) {
        if (creationTimestamp == null || creationTimestamp.isBlank()) {
            LOGGER.warn("skipping workspace {} without creationTimestamp, name={}", kind, name);
            return false;
        }
        try {
            var createdAt = ZonedDateTime.parse(creationTimestamp).toInstant();
            return !createdAt.isAfter(cutoff);
        } catch (DateTimeParseException e) {
            LOGGER.warn("skipping workspace {} with invalid creationTimestamp, name={}, creationTimestamp={}",
                    kind, name, creationTimestamp);
            return false;
        }
    }

    record OrphanResult(int pvcsDeleted, int sandboxesDeleted) {
        static final OrphanResult EMPTY = new OrphanResult(0, 0);
    }
}
