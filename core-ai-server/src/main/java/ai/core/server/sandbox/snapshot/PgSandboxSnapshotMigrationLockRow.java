package ai.core.server.sandbox.snapshot;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSandboxSnapshotMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
