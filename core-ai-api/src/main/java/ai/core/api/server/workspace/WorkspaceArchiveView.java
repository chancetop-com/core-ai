package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class WorkspaceArchiveView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "workspace_id")
    public String workspaceId;

    @NotNull
    @Property(name = "volume_generation")
    public Long volumeGeneration;

    @NotNull
    @Property(name = "sha256")
    public String sha256;

    @NotNull
    @Property(name = "size_bytes")
    public Long sizeBytes;

    @Property(name = "file_count")
    public Long fileCount;

    @Property(name = "image")
    public String image;

    @NotNull
    @Property(name = "status")
    public String status;

    @Property(name = "failure_reason")
    public String failureReason;

    @Property(name = "created_by")
    public String createdBy;

    @NotNull
    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
