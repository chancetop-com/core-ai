package ai.core.server.workspace;

import ai.core.server.sandbox.agentsandbox.AgentSandboxApiVersion;
import ai.core.server.sandbox.agentsandbox.SandboxPodPlacement;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceSandboxCRBuilderTest {

    @Test
    void alphaVersionKeepsReplicasAndOmitsShutdownPolicy() {
        var cr = builder(AgentSandboxApiVersion.V1_ALPHA1).build();

        assertEquals("agents.x-k8s.io/v1alpha1", cr.get("apiVersion"));
        var spec = nested(cr, "spec");
        assertEquals(1, spec.get("replicas"));
        assertFalse(spec.containsKey("shutdownPolicy"));
    }

    @Test
    void betaVersionDropsReplicasAndDeletesRuntimeOnShutdown() {
        var cr = builder(AgentSandboxApiVersion.V1_BETA1).build();

        assertEquals("agents.x-k8s.io/v1beta1", cr.get("apiVersion"));
        var spec = nested(cr, "spec");
        assertFalse(spec.containsKey("replicas"));
        assertEquals("Delete", spec.get("shutdownPolicy"));
    }

    @Test
    void podTemplateCarriesWorkspaceLabelsAndPersistentMount() {
        var cr = builder(AgentSandboxApiVersion.V1_BETA1).build();

        var podTemplate = nested(nested(cr, "spec"), "podTemplate");
        var labels = labels(podTemplate);
        assertEquals("ws-1", labels.get("core-ai/workspace-id"));
        assertEquals("0", labels.get("core-ai/runtime-generation"));

        var podSpec = nested(podTemplate, "spec");
        assertEquals(false, podSpec.get("automountServiceAccountToken"));
        var container = firstContainer(podSpec);
        var env = env(container);
        assertEquals("/workspace/home", env.get("HOME"));
        assertEquals("/workspace", env.get("WORKSPACE_DIR"));
        assertEquals("/workspace/.core-ai/env", env.get("WORKSPACE_ENV_FILE"));

        var workspaceVolume = volumes(podSpec).stream()
                .filter(v -> "workspace".equals(v.get("name")))
                .findFirst().orElseThrow();
        assertEquals("ws-1-g0", nested(workspaceVolume, "persistentVolumeClaim").get("claimName"));
    }

    @Test
    void profileDecidesRuntimeSizing() {
        var spec = new WorkspaceRuntimeSpec();
        spec.workspaceId = "ws-1";
        spec.ownerUserId = "user-1";
        spec.image = "example/workspace:1";
        spec.pvcName = "ws-1-g0";
        spec.profile = WorkspaceProfile.BUILD;

        var cr = new WorkspaceSandboxCRBuilder(spec, SandboxPodPlacement.NONE, AgentSandboxApiVersion.V1_BETA1).build();
        var podTemplate = nested(nested(cr, "spec"), "podTemplate");
        var container = firstContainer(nested(podTemplate, "spec"));

        var resources = nested(container, "resources");
        assertEquals("8192Mi", nested(resources, "limits").get("memory"));
        assertEquals("4000m", nested(resources, "limits").get("cpu"));
        assertEquals("8192Mi", nested(resources, "requests").get("memory"));
    }

    @Test
    void provisionerNameIsStableAndGenerationScoped() {
        var provisioner = new KubernetesWorkspaceProvisioner(null);

        assertEquals("ws-abcdef0123-g3", provisioner.volumeName("abcd-ef01-23", 3));
    }

    @Test
    void pvcManifestDeclaresStaticClaim() {
        var workspace = WorkspaceRecord.create("user-1", "dev", null);
        workspace.id = "ws-1";
        workspace.sizeGb = 32;
        workspace.storageClass = "managed-csi";

        var json = KubernetesWorkspaceProvisioner.manifest(workspace, "ws-ws1-g0", 0);
        var doc = JSON.fromJSON(PvcDoc.class, json);

        assertEquals("ws-ws1-g0", doc.metadata.name);
        assertEquals("ws-1", doc.metadata.labels.get("core-ai/workspace-id"));
        assertEquals("0", doc.metadata.labels.get("core-ai/volume-generation"));
        assertEquals("managed-csi", doc.spec.storageClassName);
        assertEquals(List.of("ReadWriteOnce"), doc.spec.accessModes);
        assertEquals("32Gi", doc.spec.resources.requests.get("storage"));
    }

    private WorkspaceSandboxCRBuilder builder(AgentSandboxApiVersion version) {
        var spec = new WorkspaceRuntimeSpec();
        spec.workspaceId = "ws-1";
        spec.ownerUserId = "user-1";
        spec.image = "example/workspace:1";
        spec.pvcName = "ws-1-g0";
        spec.runtimeGeneration = 0;
        return new WorkspaceSandboxCRBuilder(spec, SandboxPodPlacement.NONE, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> labels(Map<String, Object> podTemplate) {
        return (Map<String, String>) nested(podTemplate, "metadata").get("labels");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstContainer(Map<String, Object> podSpec) {
        return ((List<Map<String, Object>>) podSpec.get("containers")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> volumes(Map<String, Object> podSpec) {
        return (List<Map<String, Object>>) podSpec.get("volumes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> env(Map<String, Object> container) {
        var env = (List<Map<String, String>>) container.get("env");
        return env.stream().collect(Collectors.toMap(e -> e.get("name"), e -> e.get("value")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    public static class PvcDoc {
        @Property(name = "metadata")
        public Metadata metadata;
        @Property(name = "spec")
        public Spec spec;

        public static class Metadata {
            @Property(name = "name")
            public String name;
            @Property(name = "labels")
            public Map<String, String> labels;
        }

        public static class Spec {
            @Property(name = "storageClassName")
            public String storageClassName;
            @Property(name = "accessModes")
            public List<String> accessModes;
            @Property(name = "resources")
            public Resources resources;
        }

        public static class Resources {
            @Property(name = "requests")
            public Map<String, String> requests;
        }
    }
}
