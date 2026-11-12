package ai.core.server.agent;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
