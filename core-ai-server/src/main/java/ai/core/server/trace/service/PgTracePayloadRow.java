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

    @Column(name = "object_hash")
    public String objectHash;

    @Column(name = "storage_key")
    public String storageKey;

    @Column(name = "content_type")
    public String contentType;

    @Column(name = "size_bytes")
    public Long sizeBytes;
}
