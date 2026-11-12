package ai.core.server.schedule;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSessionScheduleMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
