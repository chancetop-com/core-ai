package ai.core.server.session;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgChatMessageMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
