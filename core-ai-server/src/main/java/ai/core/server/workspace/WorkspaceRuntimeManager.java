package ai.core.server.workspace;

import ai.core.server.sandbox.agentsandbox.AgentSandboxApiVersion;
import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.agentsandbox.SandboxPodPlacement;
import core.framework.json.JSON;

import java.util.Optional;

/**
 * Creates, adopts and deletes the Sandbox CR behind a workspace runtime. Adoption is deliberately
 * strict: an existing CR is only accepted when its labels name this workspace and this runtime
 * generation, so a name collision can never mount someone else's volume.
 *
 * @author stephen
 */
public class WorkspaceRuntimeManager {
    private final AgentSandboxClient client;
    private final SandboxPodPlacement placement;
    private final AgentSandboxApiVersion apiVersion;

    public WorkspaceRuntimeManager(AgentSandboxClient client, SandboxPodPlacement placement, AgentSandboxApiVersion apiVersion) {
        this.client = client;
        this.placement = placement == null ? SandboxPodPlacement.NONE : placement;
        this.apiVersion = apiVersion == null ? AgentSandboxApiVersion.V1_ALPHA1 : apiVersion;
    }

    public boolean available() {
        return client != null;
    }

    public String name(WorkspaceRuntimeSpec spec) {
        return new WorkspaceSandboxCRBuilder(spec, placement, apiVersion).sandboxName();
    }

    public String create(WorkspaceRuntimeSpec spec) {
        var builder = new WorkspaceSandboxCRBuilder(spec, placement, apiVersion);
        var name = builder.sandboxName();
        var existing = client.getSandbox(name);
        if (existing.isPresent()) {
            validate(existing.get(), spec);
            return name;
        }
        try {
            client.createSandbox(JSON.toJSON(builder.build()));
        } catch (RuntimeException e) {
            // a concurrent replica may have created the same CR between our GET and POST
            var adopted = client.getSandbox(name);
            if (adopted.isEmpty()) throw e;
            validate(adopted.get(), spec);
        }
        return name;
    }

    public void waitReady(String name, int timeoutSeconds) {
        client.waitForRuntimeReady(name, timeoutSeconds * 1000);
    }

    public void delete(String name) {
        client.deleteSandbox(name);
    }

    public Optional<AgentSandboxClient.SandboxCR> get(String name) {
        return client.getSandbox(name);
    }

    public Optional<String> endpointHost(String name) {
        if (client == null || name == null || name.isBlank()) return Optional.empty();
        var cr = client.getSandbox(name).orElse(null);
        if (cr == null || cr.status == null) return Optional.empty();
        if (cr.status.serviceFQDN != null && !cr.status.serviceFQDN.isBlank()) {
            return Optional.of(cr.status.serviceFQDN);
        }
        if (cr.status.podIPs != null && cr.status.podIPs.length > 0
                && cr.status.podIPs[0] != null && !cr.status.podIPs[0].isBlank()) {
            return Optional.of(cr.status.podIPs[0]);
        }
        return Optional.empty();
    }

    private void validate(AgentSandboxClient.SandboxCR cr, WorkspaceRuntimeSpec spec) {
        var labels = cr.metadata != null ? cr.metadata.labels : null;
        var workspaceLabel = labels != null ? labels.get("core-ai/workspace-id") : null;
        var generationLabel = labels != null ? labels.get("core-ai/runtime-generation") : null;
        if (!WorkspaceIds.label(spec.workspaceId).equals(workspaceLabel)
                || !String.valueOf(spec.runtimeGeneration).equals(generationLabel)) {
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED,
                    "sandbox name is taken by another workspace: " + (cr.metadata != null ? cr.metadata.name : "<unknown>"));
        }
    }
}
