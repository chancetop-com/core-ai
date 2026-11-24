package ai.core.server.sandbox.agentsandbox;

/**
 * The agents.x-k8s.io API version every Sandbox writer must use. Chat sandboxes and workspace
 * sandboxes share this value: a half-upgraded cluster where one path still writes v1alpha1 while
 * the CRD serves v1beta1 is not a supported state.
 *
 * <p>The only shape difference: v1alpha1 requires {@code spec.replicas: 1} and has no
 * {@code shutdownPolicy}; v1beta1 forbids replicas and needs {@code shutdownPolicy: Delete},
 * because its default Retain only stops the sandbox instead of deleting it.</p>
 *
 * @author stephen
 */
public enum AgentSandboxApiVersion {
    V1_ALPHA1("v1alpha1", true),
    V1_BETA1("v1beta1", false);

    public final String value;
    public final boolean requiresReplicas;

    AgentSandboxApiVersion(String value, boolean requiresReplicas) {
        this.value = value;
        this.requiresReplicas = requiresReplicas;
    }

    public static AgentSandboxApiVersion of(String value) {
        if (value == null || value.isBlank()) return V1_ALPHA1;
        for (var version : values()) {
            if (version.value.equalsIgnoreCase(value.trim())) return version;
        }
        throw new IllegalArgumentException("unsupported agents.x-k8s.io api version: " + value);
    }
}
