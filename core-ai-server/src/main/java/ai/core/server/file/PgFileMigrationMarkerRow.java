package ai.core.server.file;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgFileMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
