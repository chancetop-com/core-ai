package ai.core.server.memory;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentMemoryMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
