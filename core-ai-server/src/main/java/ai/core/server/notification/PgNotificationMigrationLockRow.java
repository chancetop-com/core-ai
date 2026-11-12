package ai.core.server.notification;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgNotificationMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
