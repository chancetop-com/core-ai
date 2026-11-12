package ai.core.server.comparison;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgComparisonVoteCountRow {
    @Column(name = "total")
    public Long total;
}
