package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * @author xander
 */
public class AgentRunArtifact {
    @Field(name = "file_id")
    public String fileId;

    @Field(name = "file_name")
    public String fileName;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "size")
    public Long size;

    @Field(name = "source_path")
    public String sourcePath;

    @Field(name = "title")
    public String title;

    @Field(name = "description")
    public String description;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
