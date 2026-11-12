package ai.core.server.notification;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgNotificationMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
