package ai.core.server.report.store;

import core.framework.db.Column;

/**
 * One distinct value of a report parameter's options field.
 *
 * @author core-ai
 */
public class PgReportOptionRow {
    @Column(name = "option_value")
    public String value;
}
