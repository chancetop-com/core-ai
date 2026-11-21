package ai.core.server.evaluation.store;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgEvalCountRow {
    @Column(name = "total")
    public Long total;
}
