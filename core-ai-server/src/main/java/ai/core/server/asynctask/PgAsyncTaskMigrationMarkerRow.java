package ai.core.server.asynctask;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAsyncTaskMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
