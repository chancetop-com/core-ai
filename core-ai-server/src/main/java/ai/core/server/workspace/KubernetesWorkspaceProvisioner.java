package ai.core.server.workspace;

import ai.core.server.sandbox.kubernetes.KubernetesClient;
import ai.core.server.sandbox.kubernetes.PvcInfo;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes implementation: one static PVC per (workspace, volume generation), owned by the
 * WorkspaceService and never by the Sandbox. Creation does not wait for {@code Bound}: the
 * default StorageClass is WaitForFirstConsumer, so a Pending claim is usable and becomes Bound
 * when the first workspace pod is scheduled.
 *
 * @author stephen
 */
public class KubernetesWorkspaceProvisioner implements WorkspaceProvisioner {
    public static final String PROVIDER = "kubernetes";

    static String manifest(WorkspaceRecord workspace, String volumeName, long volumeGeneration) {
        var metadata = Map.of(
                "name", volumeName,
                "labels", Map.of(
                        "app.kubernetes.io/managed-by", "core-ai",
                        "core-ai/component", "workspace",
                        "core-ai/workspace-id", WorkspaceIds.label(workspace.id),
                        "core-ai/user-id", WorkspaceIds.label(workspace.ownerUserId),
                        "core-ai/volume-generation", String.valueOf(volumeGeneration)
                )
        );
        var manifest = Map.of(
                "apiVersion", "v1",
                "kind", "PersistentVolumeClaim",
                "metadata", metadata,
                "spec", Map.of(
                        "accessModes", List.of("ReadWriteOnce"),
                        "storageClassName", workspace.storageClass,
                        "resources", Map.of("requests", Map.of("storage", workspace.sizeGb + "Gi"))
                )
        );
        return JSON.toJSON(manifest);
    }

    private static void validate(WorkspaceRecord workspace, long volumeGeneration, PvcInfo pvc) {
        if ("Lost".equals(pvc.getPhase())) {
            throw new WorkspaceProvisioningException(WorkspaceProvisioningException.VOLUME_UNAVAILABLE,
                    "workspace volume is Lost: " + pvc.metadata.name);
        }
        if (pvc.status != null && pvc.status.conditions != null) {
            for (var condition : pvc.status.conditions) {
                if ("ProvisioningFailed".equals(condition.reason)) {
                    throw new WorkspaceProvisioningException(WorkspaceProvisioningException.VOLUME_UNAVAILABLE,
                            "workspace volume provisioning failed: " + condition.message);
                }
            }
        }
        if (pvc.metadata == null || pvc.metadata.labels == null) {
            throw mismatch(pvc, "missing labels");
        }
        if (!workspace.id.equals(pvc.metadata.labels.get("core-ai/workspace-id"))) {
            throw mismatch(pvc, "workspace label mismatch");
        }
        if (!String.valueOf(volumeGeneration).equals(pvc.metadata.labels.get("core-ai/volume-generation"))) {
            throw mismatch(pvc, "volume generation label mismatch");
        }
        if (pvc.spec == null || !workspace.storageClass.equals(pvc.spec.storageClassName)) {
            throw mismatch(pvc, "storage class mismatch");
        }
        if (!(workspace.sizeGb + "Gi").equals(pvc.getRequestedStorage())) {
            throw mismatch(pvc, "requested size mismatch");
        }
    }

    private static WorkspaceProvisioningException mismatch(PvcInfo pvc, String detail) {
        var name = pvc.metadata != null ? pvc.metadata.name : "<unknown>";
        return new WorkspaceProvisioningException(WorkspaceProvisioningException.VOLUME_UNAVAILABLE,
                "existing claim does not belong to this workspace volume: " + name + " (" + detail + ")");
    }

    private final KubernetesClient client;

    public KubernetesWorkspaceProvisioner(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String volumeName(String workspaceId, long volumeGeneration) {
        return "ws-" + WorkspaceIds.compact(workspaceId) + "-g" + volumeGeneration;
    }

    @Override
    public VolumeHandle ensureVolume(WorkspaceRecord workspace, long volumeGeneration) {
        requireKubernetes();
        var name = volumeName(workspace.id, volumeGeneration);
        try {
            var existing = client.getPvc(name);
            if (existing.isPresent()) {
                validate(workspace, volumeGeneration, existing.get());
                return new VolumeHandle(name, existing.get().getPhase());
            }
            var created = client.createPvc(name, manifest(workspace, name, volumeGeneration));
            validate(workspace, volumeGeneration, created);
            return new VolumeHandle(name, created.getPhase());
        } catch (WorkspaceProvisioningException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkspaceProvisioningException(WorkspaceProvisioningException.PROVISION_FAILED,
                    "failed to provision workspace volume: " + name, e);
        }
    }

    @Override
    public void deleteVolume(WorkspaceVolumeRecord volume) {
        requireKubernetes();
        try {
            client.deletePvc(volume.pvcName);
        } catch (RuntimeException e) {
            throw new WorkspaceProvisioningException(WorkspaceProvisioningException.PROVISION_FAILED,
                    "failed to delete workspace volume: " + volume.pvcName, e);
        }
    }

    private void requireKubernetes() {
        if (client == null) {
            throw new WorkspaceProvisioningException(WorkspaceProvisioningException.PROVISION_FAILED,
                    "kubernetes client is not configured for workspace volumes");
        }
    }

}


