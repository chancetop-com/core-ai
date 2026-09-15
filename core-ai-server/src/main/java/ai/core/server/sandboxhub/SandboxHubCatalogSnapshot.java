package ai.core.server.sandboxhub;

import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import core.framework.api.json.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * The session's tool set in the form that travels between replicas: the agent name plus every tool as a
 * plain DTO. Built by the replica that owns the session, so any pod can answer a script's catalog,
 * search and describe calls without holding the session itself.
 * <p>
 * Deliberately a core-ng bean and not a record: it crosses the pod-local RPC as JSON through
 * {@code ai.core.utils.JsonUtil}, whose mapper only reads {@code @Property} fields — a record
 * serializes to {@code {}} and every caller then sees nulls.
 *
 * @author xander
 */
public class SandboxHubCatalogSnapshot {
    public static SandboxHubCatalogSnapshot of(String agentName, List<SandboxHubToolDetail> details) {
        var snapshot = new SandboxHubCatalogSnapshot();
        snapshot.agentName = agentName;
        snapshot.details = details == null ? new ArrayList<>() : new ArrayList<>(details);
        return snapshot;
    }

    @Property(name = "agentName")
    public String agentName;

    @Property(name = "details")
    public List<SandboxHubToolDetail> details;

    public List<SandboxHubToolDetail> detailsOrEmpty() {
        return details == null ? List.of() : details;
    }
}
