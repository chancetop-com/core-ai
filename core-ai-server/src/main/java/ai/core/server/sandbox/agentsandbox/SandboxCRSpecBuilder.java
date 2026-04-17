package ai.core.server.sandbox.agentsandbox;

import ai.core.sandbox.SandboxConfig;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class SandboxCRSpecBuilder {
    private static final String API_GROUP = "agents.x-k8s.io";
    private static final String API_VERSION = "v1alpha1";
    private static final int RUNTIME_PORT = 8080;

    private final SandboxConfig config;
    private final String sessionId;
    private final String userId;
    private final String sandboxName;

    public SandboxCRSpecBuilder(SandboxConfig config, String sessionId, String userId) {
        this.config = config;
        this.sessionId = sessionId;
        this.userId = userId;
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        this.sandboxName = "sandbox-" + suffix;
    }

    public String sandboxName() {
        return sandboxName;
    }

    public Map<String, Object> build() {
        var cr = new LinkedHashMap<String, Object>();
        cr.put("apiVersion", API_GROUP + "/" + API_VERSION);
        cr.put("kind", "Sandbox");
        cr.put("metadata", buildMetadata());
        cr.put("spec", buildSpec());
        return cr;
    }

    private Map<String, Object> buildMetadata() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("name", sandboxName);
        metadata.put("labels", buildLabels());
        return metadata;
    }

    private Map<String, String> buildLabels() {
        var labels = new LinkedHashMap<String, String>();
        labels.put("app.kubernetes.io/managed-by", "core-ai");
        labels.put("core-ai/component", "sandbox");
        labels.put("core-ai/session-id", sanitizeLabel(sessionId != null ? sessionId : "unknown"));
        labels.put("core-ai/user-id", sanitizeLabel(userId != null ? userId : "unknown"));
        return labels;
    }

    private Map<String, Object> buildSpec() {
        var spec = new LinkedHashMap<String, Object>();
        spec.put("replicas", 1);

        var timeout = config.timeoutSeconds != null ? config.timeoutSeconds : 3600;
        spec.put("shutdownTime", Instant.now().plus(timeout, ChronoUnit.SECONDS).toString());

        spec.put("podTemplate", buildPodTemplate());
        return spec;
    }

    private Map<String, Object> buildPodTemplate() {
        var template = new LinkedHashMap<String, Object>();
        template.put("spec", buildPodSpec());
        return template;
    }

    private Map<String, Object> buildPodSpec() {
        var podSpec = new LinkedHashMap<String, Object>();
        podSpec.put("containers", List.of(buildContainer()));
        podSpec.put("volumes", buildVolumes());
        return podSpec;
    }

    private Map<String, Object> buildContainer() {
        var container = new LinkedHashMap<String, Object>();
        container.put("name", "sandbox");
        container.put("image", config.image != null ? config.image : "core-ai-sandbox-runtime:latest");
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("ports", List.of(Map.of("containerPort", RUNTIME_PORT)));

        // Resources
        var memoryLimit = (config.memoryLimitMb != null ? config.memoryLimitMb : 512) + "Mi";
        var cpuLimit = (config.cpuLimitMillicores != null ? config.cpuLimitMillicores : 500) + "m";
        container.put("resources", Map.of(
                "limits", Map.of("memory", memoryLimit, "cpu", cpuLimit),
                "requests", Map.of("memory", memoryLimit, "cpu", "100m")
        ));

        // Env
        var maxAsync = config.maxAsyncTasks != null ? config.maxAsyncTasks : 5;
        var env = new ArrayList<Map<String, String>>();
        env.add(Map.of("name", "HOME", "value", "/tmp"));
        env.add(Map.of("name", "MAX_ASYNC_TASKS", "value", String.valueOf(maxAsync)));
        container.put("env", env);

        container.put("volumeMounts", List.of(Map.of("name", "tmp", "mountPath", "/tmp")));
        return container;
    }

    private List<Map<String, Object>> buildVolumes() {
        var tmpVolume = new LinkedHashMap<String, Object>();
        tmpVolume.put("name", "tmp");
        tmpVolume.put("emptyDir", Map.of("medium", "Memory", "sizeLimit",
                config.tmpSizeLimit != null ? config.tmpSizeLimit : "100Mi"));
        return List.of(tmpVolume);
    }

    private String sanitizeLabel(String value) {
        var sanitized = value.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (sanitized.length() > 63) sanitized = sanitized.substring(0, 63);
        sanitized = sanitized.replaceAll("^[^A-Za-z0-9]+", "");
        sanitized = sanitized.replaceAll("[^A-Za-z0-9]+$", "");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }
}
