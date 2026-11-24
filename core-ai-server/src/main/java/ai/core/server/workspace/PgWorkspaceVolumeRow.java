package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceVolumeRow {
    @Column(name = "workspace_id")
    public String workspaceId;

    @Column(name = "volume_generation")
    public Long volumeGeneration;

    @Column(name = "pvc_name")
    public String pvcName;

    @Column(name = "provider")
    public String provider;

    @Column(name = "size_gb")
    public Integer sizeGb;

    @Column(name = "storage_class")
    public String storageClass;

    @Column(name = "status")
    public String status;

    @Column(name = "node_name")
    public String nodeName;

    @Column(name = "zone")
    public String zone;

    @Column(name = "bound_at")
    public ZonedDateTime boundAt;

    @Column(name = "deleted_at")
    public ZonedDateTime deletedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
