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
}
