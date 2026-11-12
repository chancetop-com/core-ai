package ai.core.server.costalert;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgCostAlertMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
