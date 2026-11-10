package ai.core.server.session;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgChatSessionMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
