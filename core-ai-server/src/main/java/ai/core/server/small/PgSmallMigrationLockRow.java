package ai.core.server.small;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSmallMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
