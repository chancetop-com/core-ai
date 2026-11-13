package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTraceFacetRow {
    @Column(name = "value")
    public String value;

    @Column(name = "count")
    public Long count;
}
