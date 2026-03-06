package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "file_records")
public class FileRecord {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "file_name")
    public String fileName;

    @Field(name = "content_type")
    public String contentType;

    @NotNull
    @Field(name = "size")
    public Long size;

    @NotNull
    @Field(name = "storage_path")
    public String storagePath;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
