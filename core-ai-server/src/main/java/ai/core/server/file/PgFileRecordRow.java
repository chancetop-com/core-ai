package ai.core.server.file;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgFileRecordRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "file_name")
    public String fileName;

    @Column(name = "content_type")
    public String contentType;

    @Column(name = "size")
    public Long size;

    @Column(name = "content_hash")
    public String contentHash;

    @Column(name = "storage_path")
    public String storagePath;

    @Column(name = "thumb_storage_path")
    public String thumbStoragePath;

    @Column(name = "share_token")
    public String shareToken;

    @Column(name = "shared_at")
    public ZonedDateTime sharedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;
}
