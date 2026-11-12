package ai.core.server.run;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentRunMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
