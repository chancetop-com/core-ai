package ai.core.server.asynctask;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAsyncTaskMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
