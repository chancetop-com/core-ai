package ai.core.server.sandbox.snapshot;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgSandboxSnapshotRow {
    @Column(name = "id")
    public String id;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "epoch")
    public Long epoch;

    @Column(name = "status")
    public String status;

    @Column(name = "blob_key")
    public String blobKey;

    @Column(name = "sha256")
    public String sha256;

    @Column(name = "size_bytes")
    public Long sizeBytes;

    @Column(name = "file_count")
    public Integer fileCount;

    @Column(name = "image")
    public String image;

    @Column(name = "runtime_version")
    public String runtimeVersion;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Column(name = "data")
    public String data;
}
