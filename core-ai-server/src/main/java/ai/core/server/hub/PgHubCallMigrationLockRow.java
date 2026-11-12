package ai.core.server.hub;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgHubCallMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
