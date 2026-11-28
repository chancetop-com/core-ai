package ai.core.server.workspace;

import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.kubernetes.PvcInfo;

import java.util.List;

/**
 * Narrow cluster boundary used by the lifecycle sweeper to list and delete orphaned workspace
 * PVCs and Sandbox CRs. Non-Kubernetes providers supply a no-op implementation.
 *
 * @author stephen
 */
public interface WorkspaceOrphanScanner {
    static WorkspaceOrphanScanner noop() {
        return new KubernetesWorkspaceOrphanScanner(null, null);
    }

    List<PvcInfo> listWorkspacePvcs();

    void deletePvc(String pvcName);

    List<AgentSandboxClient.SandboxCR> listWorkspaceSandboxes();

    void deleteSandbox(String sandboxName);
}
