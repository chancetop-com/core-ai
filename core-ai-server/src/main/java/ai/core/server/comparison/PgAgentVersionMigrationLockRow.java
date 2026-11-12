package ai.core.server.comparison;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentVersionMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
