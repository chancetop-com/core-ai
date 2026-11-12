package ai.core.server.hub;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgHubCallCountRow {
    @Column(name = "total")
    public Long total;
}
