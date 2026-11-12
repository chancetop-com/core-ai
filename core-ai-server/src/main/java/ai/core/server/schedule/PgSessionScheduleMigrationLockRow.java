package ai.core.server.schedule;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSessionScheduleMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
