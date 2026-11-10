package ai.core.server.file;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgFileRecordCountRow {
    @Column(name = "total")
    public Long total;
}
