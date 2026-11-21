package ai.core.server.evaluation.store;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgEvalNextRow {
    @Column(name = "next_revision")
    public Integer nextRevision;
}
