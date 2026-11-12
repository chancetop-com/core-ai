package ai.core.server.comparison;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentVersionMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
