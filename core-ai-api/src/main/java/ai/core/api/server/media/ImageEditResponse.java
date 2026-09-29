package ai.core.api.server.media;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ImageEditResponse {
    @Property(name = "mediaJobId")
    public String mediaJobId;

    @Property(name = "fileId")
    public String fileId;

    @Property(name = "url")
    public String url;

    @Property(name = "mediaId")
    public String mediaId;

    @Property(name = "model")
    public String model;

    /** "mask" when the model received the mask itself, "annotation" when the region was approximated. */
    @Property(name = "maskMode")
    public String maskMode;

    /** Where the result is stored, so a caller can hand it back as an attachment (object storage blob). */
    @Property(name = "container")
    public String container;

    @Property(name = "blobName")
    public String blobName;

    @Property(name = "fileName")
    public String fileName;

    @Property(name = "notes")
    public List<String> notes;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "costSource")
    public String costSource;

    @Property(name = "elapsedMs")
    public Long elapsedMs;
}
