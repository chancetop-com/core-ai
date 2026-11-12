package ai.core.server.schedule;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSessionScheduleCountRow {
    @Column(name = "total")
    public Long total;
}
