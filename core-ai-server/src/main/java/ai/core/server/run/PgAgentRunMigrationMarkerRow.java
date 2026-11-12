package ai.core.server.run;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentRunMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
