package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTraceTraceIdRow {
    @Column(name = "trace_id")
    public String traceId;
}
