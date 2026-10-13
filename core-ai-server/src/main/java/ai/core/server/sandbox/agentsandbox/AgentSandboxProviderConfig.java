package ai.core.server.sandbox.agentsandbox;

import ai.core.sandbox.SandboxConfig;
import ai.core.server.sandbox.kubernetes.KubernetesClient;

/**
 * @author stephen
 */
public class AgentSandboxProviderConfig {
    public AgentSandboxClient client;
    public AgentSandboxExtensionsClient extensionsClient;
    public SandboxConfig defaultConfig;
    public KubernetesClient kubernetesClient;
    public boolean useHostPort;
    public String templateName;
    public String warmPoolName;
    /** Placement of directly created sandbox pods; claims inherit the pool template's. */
    public SandboxPodPlacement podPlacement = SandboxPodPlacement.NONE;
}
