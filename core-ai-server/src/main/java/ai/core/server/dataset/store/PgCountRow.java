package ai.core.server.dataset.store;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgCountRow {
    @Column(name = "total")
    public Long total;
}
