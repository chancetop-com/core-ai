package ai.core.server.sandbox.snapshot;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * One captured sandbox filesystem snapshot. Two-phase visibility: only status
 * AVAILABLE may be restored; DELETED means the blob still needs cleanup retry.
 *
 * @author xander
 */
@Collection(name = "sandbox_snapshots")
public class SandboxSnapshotDoc {
    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    public String id;

    @NotNull
    @Field(name = "session_id")
    public String sessionId;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "epoch")
    public Long epoch;

    @NotNull
    @Field(name = "status")
    public String status;

    @NotNull
    @Field(name = "blob_key")
    public String blobKey;

    @Field(name = "sha256")
    public String sha256;

    @Field(name = "size_bytes")
    public Long sizeBytes;

    @Field(name = "file_count")
    public Integer fileCount;

    @Field(name = "image")
    public String image;

    @Field(name = "runtime_version")
    public String runtimeVersion;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "expires_at")
    public ZonedDateTime expiresAt;
}
