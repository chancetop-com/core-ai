package ai.core.server.comparison;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgComparisonMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
