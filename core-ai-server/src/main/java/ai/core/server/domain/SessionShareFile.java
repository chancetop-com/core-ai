package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Field;

/**
 * An allowlisted artifact or attachment with a public identifier independent of its storage location.
 *
 * @author Xander
 */
public class SessionShareFile {
    @NotNull
    @Field(name = "id")
    public String id;

    @Field(name = "file_id")
    public String fileId;

    @Field(name = "attachment_ref_id")
    public String attachmentRefId;

    @Field(name = "container")
    public String container;

    @Field(name = "blob_name")
    public String blobName;

    @Field(name = "file_name")
    public String fileName;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "size")
    public Long size;

    @Field(name = "source_url")
    public String sourceUrl;

    @Field(name = "title")
    public String title;

    @Field(name = "description")
    public String description;
}
