package ai.core.server.replay;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgReplayMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
