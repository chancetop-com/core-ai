package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceLeaseRow {
    @Column(name = "workspace_id")
    public String workspaceId;

    @Column(name = "holder_id")
    public String holderId;

    @Column(name = "lease_token")
    public String leaseToken;

    @Column(name = "lease_kind")
    public String leaseKind;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "turn_id")
    public String turnId;

    @Column(name = "runtime_generation")
    public Long runtimeGeneration;

    @Column(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Column(name = "renewed_at")
    public ZonedDateTime renewedAt;
}
