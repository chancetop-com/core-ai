package ai.core.server.task;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgBackgroundTaskMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
