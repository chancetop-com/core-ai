package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * A downstream/caller-facing file reference produced by a workflow run or node — identity, an absolute download
 * URL and metadata, never the bytes.
 *
 * @author Xander
 */
public class ArtifactView {
    @Property(name = "file_id")
    public String fileId;

    @Property(name = "file_name")
    public String fileName;

    @Property(name = "content_type")
    public String contentType;

    @Property(name = "size")
    public Long size;

    @Property(name = "url")
    public String url;

    @Property(name = "title")
    public String title;

    @Property(name = "description")
    public String description;
}
