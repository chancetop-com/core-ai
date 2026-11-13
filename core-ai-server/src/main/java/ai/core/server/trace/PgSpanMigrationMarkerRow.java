package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSpanMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
