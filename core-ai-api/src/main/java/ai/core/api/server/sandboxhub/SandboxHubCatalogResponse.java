package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * The session's whole exposed capability set, fetched once by the SDK/CLI at startup.
 * <p>
 * {@code groups} describes how tools are grouped (one MCP server, one API app, one singleton kind)
 * so a client can build namespaces without re-deriving them from {@code ref_id}s.
 *
 * @author stephen
 */
public class SandboxHubCatalogResponse {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "sandbox_id")
    public String sandboxId;

    @Property(name = "sandbox_state")
    public String sandboxState;

    @Property(name = "expires_at")
    public String expiresAt;

    @Property(name = "contract_version")
    public String contractVersion;

    @Property(name = "groups")
    public List<SandboxHubGroup> groups;

    @Property(name = "tools")
    public List<SandboxHubToolSummary> tools;
}
