package ai.core.server.sandbox.snapshot;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgSandboxEpochRow {
    @Column(name = "id")
    public String id;

    @Column(name = "epoch")
    public Long epoch;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
