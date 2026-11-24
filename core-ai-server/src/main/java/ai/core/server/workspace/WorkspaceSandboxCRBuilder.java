package ai.core.server.workspace;

import ai.core.sandbox.SandboxConstants;
import ai.core.server.sandbox.agentsandbox.AgentSandboxApiVersion;
import ai.core.server.sandbox.agentsandbox.SandboxPodPlacement;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the direct Sandbox CR for a workspace. This intentionally does not reuse or revive
 * {@code SandboxCRSpecBuilder}: a workspace needs a static PVC mount, a persistent HOME and a
 * shutdown policy that keeps the volume while deleting the runtime. The only shared piece is
 * {@link AgentSandboxApiVersion}, so chat and workspace never disagree on the CRD version.
 *
 * @author stephen
 */
public class WorkspaceSandboxCRBuilder {
    private static final String API_GROUP = "agents.x-k8s.io";
    private static final int RUNTIME_PORT = 8080;
    // the controller may act slightly after our own max runtime; keep the extra window explicit
    private static final int SHUTDOWN_GRACE_SECONDS = 600;

    private final WorkspaceRuntimeSpec spec;
    private final SandboxPodPlacement placement;
    private final AgentSandboxApiVersion apiVersion;

    public WorkspaceSandboxCRBuilder(WorkspaceRuntimeSpec spec, SandboxPodPlacement placement, AgentSandboxApiVersion apiVersion) {
        this.spec = spec;
        this.placement = placement == null ? SandboxPodPlacement.NONE : placement;
        this.apiVersion = apiVersion == null ? AgentSandboxApiVersion.V1_ALPHA1 : apiVersion;
    }

    public String sandboxName() {
        return "ws-" + WorkspaceIds.compact(spec.workspaceId) + "-" + spec.runtimeGeneration;
    }

    public Map<String, Object> build() {
        var cr = new LinkedHashMap<String, Object>();
        cr.put("apiVersion", API_GROUP + "/" + apiVersion.value);
        cr.put("kind", "Sandbox");
        cr.put("metadata", Map.of("name", sandboxName(), "labels", buildLabels()));
        cr.put("spec", buildSpec());
        return cr;
    }

    private Map<String, Object> buildSpec() {
        var spec = new LinkedHashMap<String, Object>();
        if (apiVersion.requiresReplicas) {
            spec.put("replicas", 1);
        } else {
            spec.put("shutdownPolicy", "Delete");
        }
        spec.put("shutdownTime", Instant.now()
                .plus(this.spec.maxRuntimeSeconds + SHUTDOWN_GRACE_SECONDS, ChronoUnit.SECONDS).toString());
        spec.put("podTemplate", buildPodTemplate());
        return spec;
    }

    private Map<String, Object> buildPodTemplate() {
        // labels must be on the pod template, not only on the CR: NetworkPolicy selects pods
        return Map.of("metadata", Map.of("labels", buildLabels()), "spec", buildPodSpec());
    }

    private Map<String, String> buildLabels() {
        var labels = new LinkedHashMap<String, String>();
        labels.put("app.kubernetes.io/managed-by", "core-ai");
        labels.put("core-ai/component", "workspace");
        labels.put("core-ai/workspace-id", WorkspaceIds.label(spec.workspaceId));
        labels.put("core-ai/user-id", WorkspaceIds.label(spec.ownerUserId));
        labels.put("core-ai/runtime-generation", String.valueOf(spec.runtimeGeneration));
        return labels;
    }

    private Map<String, Object> buildPodSpec() {
        var podSpec = new LinkedHashMap<String, Object>();
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("containers", List.of(buildContainer()));
        podSpec.put("volumes", buildVolumes());
        if (placement.priorityClassName != null && !placement.priorityClassName.isBlank()) {
            podSpec.put("priorityClassName", placement.priorityClassName);
        }
        if (placement.nodeSelector != null) {
            podSpec.put("nodeSelector", placement.nodeSelector);
        }
        return podSpec;
    }

    private Map<String, Object> buildContainer() {
        var container = new LinkedHashMap<String, Object>();
        container.put("name", "sandbox");
        container.put("image", spec.image);
        container.put("imagePullPolicy", "Always");
        container.put("ports", List.of(Map.of("containerPort", RUNTIME_PORT)));

        var memory = spec.profile.memoryMb + "Mi";
        var cpu = spec.profile.cpuMillicores + "m";
        container.put("resources", Map.of(
                "limits", Map.of("memory", memory, "cpu", cpu),
                "requests", Map.of("memory", memory, "cpu", cpu)
        ));

        container.put("env", buildEnv());
        container.put("volumeMounts", List.of(
                Map.of("name", "workspace", "mountPath", WorkspaceDefaults.MOUNT_PATH),
                Map.of("name", "tmp", "mountPath", "/tmp"),
                Map.of("name", "skill", "mountPath", "/skill")
        ));
        return container;
    }

    private List<Map<String, String>> buildEnv() {
        var env = new ArrayList<Map<String, String>>();
        env.add(Map.of("name", "WORKSPACE_DIR", "value", WorkspaceDefaults.MOUNT_PATH));
        env.add(Map.of("name", "HOME", "value", WorkspaceDefaults.MOUNT_PATH + "/home"));
        env.add(Map.of("name", "WORKSPACE_ENV_FILE", "value", WorkspaceDefaults.MOUNT_PATH + "/.core-ai/env"));
        env.add(Map.of("name", "MAX_ASYNC_TASKS", "value", "10"));
        env.add(Map.of("name", "ASYNC_MAX_TIMEOUT_MS", "value", "7200000"));
        env.add(Map.of("name", "ASYNC_TASK_RETENTION_MS", "value", "7200000"));
        for (var entry : SandboxConstants.SANDBOX_GIT_ENV.entrySet()) {
            env.add(Map.of("name", entry.getKey(), "value", entry.getValue()));
        }
        return env;
    }

    private List<Map<String, Object>> buildVolumes() {
        var workspace = Map.<String, Object>of("name", "workspace",
                "persistentVolumeClaim", Map.of("claimName", spec.pvcName));
        var tmp = Map.<String, Object>of("name", "tmp", "emptyDir", Map.of("sizeLimit", "10Gi"));
        var skill = Map.<String, Object>of("name", "skill", "emptyDir", Map.of("sizeLimit", "50Mi"));
        return List.of(workspace, tmp, skill);
    }
}
