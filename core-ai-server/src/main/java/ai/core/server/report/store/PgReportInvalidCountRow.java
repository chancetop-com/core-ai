package ai.core.server.report.store;

import core.framework.db.Column;

/**
 * @author core-ai
 */
public class PgReportInvalidCountRow {
    @Column(name = "invalid")
    public Long invalid;
}
