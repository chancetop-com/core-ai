package ai.core.server.sandbox.kubernetes;

import ai.core.sandbox.SandboxConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class KubernetesPodSpecBuilder {

    private static final String SANDBOX_USER = "1001";
    private static final String SANDBOX_GROUP = "1001";
    private static final String RUNTIME_PORT = "8080";

    private final String podName;
    private final SandboxConfig config;
    private final String sessionId;
    private final String userId;

    public KubernetesPodSpecBuilder(SandboxConfig config, String sessionId, String userId) {
        this.config = config;
        this.sessionId = sessionId;
        this.userId = userId;
        // Generate unique pod name
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        this.podName = "sandbox-" + suffix;
    }

    public String podName() {
        return podName;
    }

    public Map<String, Object> build() {
        var pod = new LinkedHashMap<String, Object>();
        pod.put("apiVersion", "v1");
        pod.put("kind", "Pod");
        pod.put("metadata", buildMetadata());
        pod.put("spec", buildSpec());
        return pod;
    }

    private Map<String, Object> buildMetadata() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("name", podName);
        metadata.put("generateName", "sandbox-");
        metadata.put("labels", buildLabels());
        return metadata;
    }

    private Map<String, String> buildLabels() {
        var labels = new LinkedHashMap<String, String>();
        labels.put("component", "sandbox");
        labels.put("session-id", sessionId != null ? sessionId : "unknown");
        labels.put("user-id", userId != null ? userId : "unknown");
        if (Boolean.TRUE.equals(config.networkEnabled)) {
            labels.put("network-enabled", "true");
        }
        return labels;
    }

    private Map<String, Object> buildSpec() {
        var spec = new LinkedHashMap<String, Object>();
        spec.put("restartPolicy", "Never");
        spec.put("terminationGracePeriodSeconds", 5);
        spec.put("securityContext", buildPodSecurityContext());
        spec.put("containers", List.of(buildContainer()));
        spec.put("volumes", buildVolumes());
        return spec;
    }

    private Map<String, Object> buildPodSecurityContext() {
        var securityContext = new LinkedHashMap<String, Object>();
        securityContext.put("runAsNonRoot", true);
        securityContext.put("runAsUser", Integer.parseInt(SANDBOX_USER));
        securityContext.put("runAsGroup", Integer.parseInt(SANDBOX_GROUP));
        securityContext.put("fsGroup", Integer.parseInt(SANDBOX_GROUP));
        securityContext.put("seccompProfile", Map.of("type", "RuntimeDefault"));
        return securityContext;
    }

    private Map<String, Object> buildContainer() {
        var container = new LinkedHashMap<String, Object>();
        container.put("name", "sandbox");
        container.put("image", config.image != null ? config.image : "core-ai-sandbox:latest");
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("ports", List.of(Map.of("containerPort", Integer.parseInt(RUNTIME_PORT))));
        container.put("securityContext", buildContainerSecurityContext());
        container.put("resources", buildResources());
        container.put("env", buildEnv());
        container.put("volumeMounts", buildVolumeMounts());
        container.put("livenessProbe", buildLivenessProbe());
        container.put("readinessProbe", buildReadinessProbe());
        return container;
    }

    private Map<String, Object> buildContainerSecurityContext() {
        var securityContext = new LinkedHashMap<String, Object>();
        securityContext.put("allowPrivilegeEscalation", false);
        securityContext.put("readOnlyRootFilesystem", true);
        securityContext.put("capabilities", Map.of("drop", List.of("ALL")));
        return securityContext;
    }

    private Map<String, Object> buildResources() {
        var resources = new LinkedHashMap<String, Object>();
        var limits = new LinkedHashMap<String, Object>();
        var requests = new LinkedHashMap<String, Object>();

        var memoryLimit = (config.memoryLimitMb != null ? config.memoryLimitMb : 512) + "Mi";
        var cpuLimit = config.cpuLimitMillicores != null ? config.cpuLimitMillicores : 500;

        limits.put("memory", memoryLimit);
        limits.put("cpu", cpuLimit + "m");
        requests.put("memory", memoryLimit);
        requests.put("cpu", "100m"); // Request minimum CPU

        resources.put("limits", limits);
        resources.put("requests", requests);
        return resources;
    }

    private List<Map<String, String>> buildEnv() {
        var maxAsync = config.maxAsyncTasks != null ? config.maxAsyncTasks : 5;
        var env = new ArrayList<Map<String, String>>();
        env.add(Map.of("name", "HOME", "value", "/tmp"));
        env.add(Map.of("name", "LANG", "value", "en_US.UTF-8"));
        env.add(Map.of("name", "PYTHONIOENCODING", "value", "utf-8"));
        env.add(Map.of("name", "MAX_ASYNC_TASKS", "value", String.valueOf(maxAsync)));
        return env;
    }

    private Map<String, Object> buildLivenessProbe() {
        var probe = new LinkedHashMap<String, Object>();
        probe.put("httpGet", Map.of("path", "/health", "port", Integer.parseInt(RUNTIME_PORT)));
        probe.put("initialDelaySeconds", 5);
        probe.put("periodSeconds", 10);
        probe.put("timeoutSeconds", 5);
        probe.put("failureThreshold", 3);
        return probe;
    }

    private Map<String, Object> buildReadinessProbe() {
        var probe = new LinkedHashMap<String, Object>();
        probe.put("httpGet", Map.of("path", "/health", "port", Integer.parseInt(RUNTIME_PORT)));
        probe.put("initialDelaySeconds", 2);
        probe.put("periodSeconds", 5);
        probe.put("timeoutSeconds", 3);
        probe.put("failureThreshold", 1);
        return probe;
    }

    private List<Map<String, Object>> buildVolumeMounts() {
        var mounts = new ArrayList<Map<String, Object>>();
        var tmpMount = new LinkedHashMap<String, Object>();
        tmpMount.put("name", "tmp");
        tmpMount.put("mountPath", "/tmp");
        mounts.add(tmpMount);
        return mounts;
    }

    private List<Map<String, Object>> buildVolumes() {
        var volumes = new ArrayList<Map<String, Object>>();

        // Empty dir for /tmp (writable)
        var tmpVolume = new LinkedHashMap<String, Object>();
        tmpVolume.put("name", "tmp");
        var tmpEmptyDir = new LinkedHashMap<String, Object>();
        tmpEmptyDir.put("medium", "Memory");
        if (config.tmpSizeLimit != null) {
            tmpEmptyDir.put("sizeLimit", config.tmpSizeLimit);
        } else {
            tmpEmptyDir.put("sizeLimit", "100Mi");
        }
        tmpVolume.put("emptyDir", tmpEmptyDir);
        volumes.add(tmpVolume);

        return volumes;
    }
}
