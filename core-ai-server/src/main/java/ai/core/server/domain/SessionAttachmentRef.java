package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "session_attachment_ref")
public class SessionAttachmentRef {
    @Id
    public String id;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "container")
    public String container;

    @Field(name = "blob_name")
    public String blobName;

    @Field(name = "source_etag")
    public String sourceETag;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "file_name")
    public String fileName;

    @Field(name = "source_size_bytes")
    public Long sourceSizeBytes;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
