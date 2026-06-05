package ai.core.api.server.artifact;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

public class SharedArtifactView {
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
    @Property(name = "user_id")
    public String userId;

    @NotNull
    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "shared_at")
    public ZonedDateTime sharedAt;
}
