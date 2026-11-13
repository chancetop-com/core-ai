package ai.core.api.server.share;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class SharedSessionArtifact {
    @Property(name = "file_id")
    public String fileId;

    @Property(name = "file_name")
    public String fileName;

    @Property(name = "content_type")
    public String contentType;

    @Property(name = "size")
    public Long size;

    @Property(name = "title")
    public String title;

    @Property(name = "description")
    public String description;

    @Property(name = "download_url")
    public String downloadUrl;
}
