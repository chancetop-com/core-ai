package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class SkillResource {
    @Field(name = "path")
    public String path;

    @Field(name = "content")
    public String content;

    @Field(name = "storage_path")
    public String storagePath;

    @Field(name = "size")
    public Long size;

    @Field(name = "sha256")
    public String sha256;

    @Field(name = "content_type")
    public String contentType;
}
