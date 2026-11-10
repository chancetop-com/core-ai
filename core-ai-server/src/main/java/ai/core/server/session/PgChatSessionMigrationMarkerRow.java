package ai.core.server.session;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgChatSessionMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
