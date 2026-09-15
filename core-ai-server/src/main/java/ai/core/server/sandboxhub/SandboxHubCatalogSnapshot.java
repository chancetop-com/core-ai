package ai.core.server.sandboxhub;

import ai.core.api.server.sandboxhub.SandboxHubToolDetail;

import java.util.List;

/**
 * The session's tool set in the form that travels between replicas: the agent name plus every tool as a
 * plain DTO. Built by the replica that owns the session, so any pod can answer a script's catalog,
 * search and describe calls without holding the session itself.
 *
 * @author xander
 */
public record SandboxHubCatalogSnapshot(String agentName, List<SandboxHubToolDetail> details) {
}
