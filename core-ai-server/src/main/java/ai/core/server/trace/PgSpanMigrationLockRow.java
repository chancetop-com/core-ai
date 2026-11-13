package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSpanMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
