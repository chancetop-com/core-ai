package ai.core.server.agent;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
