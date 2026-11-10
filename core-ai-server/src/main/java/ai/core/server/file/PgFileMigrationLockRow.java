package ai.core.server.file;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgFileMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
