package ai.core.server.trace.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * One unique inline payload (currently an image data URL) referenced by a trace.
 * Scoped by trace id so the row can be deleted together with the trace after archiving.
 *
 * @author stephen
 */
@Collection(name = "trace_payload_blobs")
public class TracePayloadBlob {
    @Id
    public String id; // traceId + ":" + sha256(dataUrl)

    @Field(name = "trace_id")
    public String traceId;

    @Field(name = "data")
    public String data;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "size_bytes")
    public Long sizeBytes;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
