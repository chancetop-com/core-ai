package ai.core.server.pg;

import core.framework.db.Column;

/**
 * One-row document projection: the entity is stored as a single JSON document so a table can
 * carry flexible fields without a core-ng view per column.
 *
 * @author stephen
 */
public class PgDocumentRow {
    @Column(name = "data")
    public String data;
}
