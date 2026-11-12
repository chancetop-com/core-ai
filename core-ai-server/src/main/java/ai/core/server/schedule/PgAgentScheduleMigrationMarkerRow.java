package ai.core.server.schedule;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentScheduleMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
