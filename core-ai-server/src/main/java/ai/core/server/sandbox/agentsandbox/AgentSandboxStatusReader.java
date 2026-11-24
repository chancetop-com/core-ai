package ai.core.server.sandbox.agentsandbox;

import ai.core.sandbox.SandboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the live Kubernetes status of an agent sandbox. A missing resource is reported as
 * terminated; missing clients or status sections stay non-terminal so a still-running sandbox is
 * never torn down on an unverifiable read.
 *
 * @author stephen
 */
public class AgentSandboxStatusReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandboxStatusReader.class);

    private final AgentSandboxClient client;
    private final AgentSandboxExtensionsClient extensionsClient;

    public AgentSandboxStatusReader(AgentSandboxClient client, AgentSandboxExtensionsClient extensionsClient) {
        this.client = client;
        this.extensionsClient = extensionsClient;
    }

    public SandboxStatus status(AgentSandbox sandbox) {
        return sandbox.kind() == AgentSandboxKind.CLAIM ? claimStatus(sandbox) : directStatus(sandbox);
    }

    private SandboxStatus claimStatus(AgentSandbox sandbox) {
        if (extensionsClient == null) {
            // Cannot verify: reporting a terminal state here would destroy a sandbox that is still alive.
            LOGGER.warn("no extensions client configured, keeping local sandbox status: name={}", sandbox.getId());
            return sandbox.getStatus();
        }
        var opt = extensionsClient.getClaim(sandbox.getId());
        if (opt.isEmpty()) return SandboxStatus.TERMINATED;
        var claim = opt.get();
        if (claim.status == null) return SandboxStatus.CREATING;
        if (claim.status.conditions != null) {
            for (var c : claim.status.conditions) {
                if ("Ready".equals(c.type) && "True".equals(c.status)) {
                    return sandbox.getStatus() == SandboxStatus.EXECUTING ? SandboxStatus.EXECUTING : SandboxStatus.READY;
                }
                if ("Failed".equals(c.type) && "True".equals(c.status)) {
                    return SandboxStatus.ERROR;
                }
            }
        }
        if (claim.status.sandbox != null && claim.status.sandbox.podIPs != null && claim.status.sandbox.podIPs.length > 0) {
            return SandboxStatus.READY;
        }
        return SandboxStatus.CREATING;
    }

    private SandboxStatus directStatus(AgentSandbox sandbox) {
        var opt = client.getSandbox(sandbox.getId());
        if (opt.isEmpty()) return SandboxStatus.TERMINATED;
        var cr = opt.get();
        if (cr.status == null) return SandboxStatus.CREATING;
        if (cr.status.conditions != null) {
            for (var c : cr.status.conditions) {
                if ("Ready".equals(c.type) && "True".equals(c.status)) {
                    return sandbox.getStatus() == SandboxStatus.EXECUTING ? SandboxStatus.EXECUTING : SandboxStatus.READY;
                }
                if ("Failed".equals(c.type) && "True".equals(c.status)) {
                    return SandboxStatus.ERROR;
                }
            }
        }
        if (cr.status.podIPs != null && cr.status.podIPs.length > 0) {
            return SandboxStatus.READY;
        }
        return SandboxStatus.CREATING;
    }
}
