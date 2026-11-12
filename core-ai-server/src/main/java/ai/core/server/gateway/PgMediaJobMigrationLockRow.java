package ai.core.server.gateway;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgMediaJobMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
