package ai.core.server.gateway;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgMediaJobMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
