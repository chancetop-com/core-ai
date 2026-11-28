package ai.core.server.workspace;

import java.time.ZonedDateTime;

/**
 * Metadata for an immutable cold archive of a workspace volume generation stored in object storage.
 *
 * @author stephen
 */
public class WorkspaceArchiveRecord {
    public String id;
    public String workspaceId;
    public long volumeGeneration;
    public String blobKey;
    public String sha256;
    public long sizeBytes;
    public Long fileCount;
    public String image;
    public WorkspaceArchiveStatus status;
    public String failureReason;
    public String createdBy;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
}
