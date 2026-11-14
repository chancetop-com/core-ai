package ai.core.server.trace.service;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTracePayloadRow {
    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "blob_hash")
    public String blobHash;

    @Column(name = "content")
    public String content;
}
