package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

/**
 * Identity of the session behind the sandbox, returned by {@code GET /api/sandbox-hub/me}.
 * <p>
 * Carries no credentials, no system prompt and no model name — a script only needs to know which
 * agent it is running for. {@code contract_version} is the wire contract the runtime was built
 * against, so a stale image can be detected instead of failing field by field.
 *
 * @author stephen
 */
public class SandboxHubSessionView {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "sandbox_id")
    public String sandboxId;

    /** Runtime-reported state; {@code ready} whenever a call can be served. */
    @Property(name = "sandbox_state")
    public String sandboxState;

    /** ISO-8601 instant at which the session token expires; the SDK may re-read it after a renewal. */
    @Property(name = "expires_at")
    public String expiresAt;

    @Property(name = "tool_count")
    public Integer toolCount;

    @Property(name = "contract_version")
    public String contractVersion;
}
