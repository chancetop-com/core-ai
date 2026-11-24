package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceBindingRow {
    @Column(name = "workspace_id")
    public String workspaceId;

    @Column(name = "sandbox_id")
    public String sandboxId;

    @Column(name = "provider")
    public String provider;

    @Column(name = "runtime_generation")
    public Long runtimeGeneration;

    @Column(name = "pod_id")
    public String podId;

    @Column(name = "bound_session_id")
    public String boundSessionId;

    @Column(name = "runtime_started_at")
    public ZonedDateTime runtimeStartedAt;

    @Column(name = "bound_at")
    public ZonedDateTime boundAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
