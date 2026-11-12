package ai.core.server.task;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgBackgroundTaskMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
