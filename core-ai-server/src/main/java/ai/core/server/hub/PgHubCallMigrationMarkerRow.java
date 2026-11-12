package ai.core.server.hub;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgHubCallMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
