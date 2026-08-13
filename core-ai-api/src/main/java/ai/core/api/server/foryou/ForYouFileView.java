package ai.core.api.server.foryou;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ForYouFileView {
    @Property(name = "id")
    public String id;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "file_name")
    public String fileName;

    @Property(name = "content_type")
    public String contentType;

    @Property(name = "size")
    public Long size;

    @Property(name = "created_at")
    public String createdAt;
}
