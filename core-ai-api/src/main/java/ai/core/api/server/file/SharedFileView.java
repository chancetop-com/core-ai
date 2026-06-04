package ai.core.api.server.file;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class SharedFileView {
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

    @NotNull
    @Property(name = "download_url")
    public String downloadUrl;
}
