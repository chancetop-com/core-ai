package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceArchiveRow {
    @Column(name = "id")
    public String id;

    @Column(name = "workspace_id")
    public String workspaceId;

    @Column(name = "volume_generation")
    public Integer volumeGeneration;

    @Column(name = "blob_key")
    public String blobKey;

    @Column(name = "sha256")
    public String sha256;

    @Column(name = "size_bytes")
    public Long sizeBytes;

    @Column(name = "file_count")
    public Long fileCount;

    @Column(name = "image")
    public String image;

    @Column(name = "status")
    public String status;

    @Column(name = "failure_reason")
    public String failureReason;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
