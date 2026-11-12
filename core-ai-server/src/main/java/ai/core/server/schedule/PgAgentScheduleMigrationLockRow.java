package ai.core.server.schedule;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentScheduleMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
