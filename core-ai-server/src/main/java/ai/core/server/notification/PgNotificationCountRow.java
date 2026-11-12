package ai.core.server.notification;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgNotificationCountRow {
    @Column(name = "total")
    public Long total;
}
