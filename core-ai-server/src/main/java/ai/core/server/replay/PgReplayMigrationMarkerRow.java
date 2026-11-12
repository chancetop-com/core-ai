package ai.core.server.replay;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgReplayMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
