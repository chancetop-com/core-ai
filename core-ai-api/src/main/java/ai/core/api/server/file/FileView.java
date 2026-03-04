package ai.core.api.server.file;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class FileView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "file_name")
    public String fileName;

    @Property(name = "content_type")
    public String contentType;

    @NotNull
    @Property(name = "size")
    public Long size;

    @NotNull
    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
