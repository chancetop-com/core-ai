package ai.core.server.costalert;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgCostAlertMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
