package ai.core.server.report.store;

import core.framework.db.Column;

/**
 * @author core-ai
 */
public class PgReportLockRow {
    @Column(name = "locked")
    public Integer locked;
}
