package ai.core.server.workspace;

import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import ai.core.server.sandbox.kubernetes.PvcInfo;

import java.util.List;

/**
 * Lists and deletes workspace-labeled PVCs and Sandbox CRs through the shared Kubernetes and
 * AgentSandbox clients. Null clients degrade to a no-op (e.g. Docker provider).
 *
 * @author stephen
 */
public class KubernetesWorkspaceOrphanScanner implements WorkspaceOrphanScanner {
    static final String WORKSPACE_SELECTOR = "core-ai/component=workspace";

    private final KubernetesClient kubernetesClient;
    private final AgentSandboxClient agentSandboxClient;

    public KubernetesWorkspaceOrphanScanner(KubernetesClient kubernetesClient, AgentSandboxClient agentSandboxClient) {
        this.kubernetesClient = kubernetesClient;
        this.agentSandboxClient = agentSandboxClient;
    }

    @Override
    public List<PvcInfo> listWorkspacePvcs() {
        if (kubernetesClient == null) return List.of();
        return kubernetesClient.listPvcs(WORKSPACE_SELECTOR);
    }

    @Override
    public void deletePvc(String pvcName) {
        if (kubernetesClient == null) return;
        kubernetesClient.deletePvc(pvcName);
    }

    @Override
    public List<AgentSandboxClient.SandboxCR> listWorkspaceSandboxes() {
        if (agentSandboxClient == null) return List.of();
        return agentSandboxClient.listSandboxes(WORKSPACE_SELECTOR);
    }

    @Override
    public void deleteSandbox(String sandboxName) {
        if (agentSandboxClient == null) return;
        agentSandboxClient.deleteSandbox(sandboxName);
    }
}
