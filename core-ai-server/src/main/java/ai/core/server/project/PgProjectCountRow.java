package ai.core.server.project;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgProjectCountRow {
    @Column(name = "total")
    public Long total;
}
