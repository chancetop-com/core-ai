package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;
import core.framework.mongo.MongoEnumValue;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "gemini_files")
public class GeminiFile {
    @Id
    public String id;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "provider_id")
    public String providerId;

    @Field(name = "upstream_model")
    public String upstreamModel;

    @Field(name = "container")
    public String container;

    @Field(name = "blob_name")
    public String blobName;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "file_name")
    public String fileName;

    @Field(name = "source_size_bytes")
    public Long sourceSizeBytes;

    @Field(name = "source_etag")
    public String sourceETag;

    @Field(name = "gemini_file_name")
    public String geminiFileName;

    @Field(name = "gemini_file_uri")
    public String geminiFileUri;

    @Field(name = "state")
    public GeminiFileState state;

    @Field(name = "failure_reason")
    public String failureReason;

    @Field(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Field(name = "upload_lease_owner")
    public String uploadLeaseOwner;

    @Field(name = "upload_lease_expires_at")
    public ZonedDateTime uploadLeaseExpiresAt;

    @Field(name = "last_verified_at")
    public ZonedDateTime lastVerifiedAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    public enum GeminiFileState {
        @MongoEnumValue("PROCESSING") PROCESSING,
        @MongoEnumValue("ACTIVE") ACTIVE,
        @MongoEnumValue("FAILED") FAILED,
        @MongoEnumValue("EXPIRED") EXPIRED
    }
}
