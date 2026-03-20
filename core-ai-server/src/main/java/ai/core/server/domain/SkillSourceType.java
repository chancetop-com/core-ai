package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum SkillSourceType {
    @MongoEnumValue("upload")
    UPLOAD,
    @MongoEnumValue("repo")
    REPO
}
