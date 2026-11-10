package ai.core.server.small;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSmallMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
