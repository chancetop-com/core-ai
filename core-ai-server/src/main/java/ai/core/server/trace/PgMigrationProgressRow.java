package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgMigrationProgressRow {
    @Column(name = "last_key")
    public String lastKey;
}
