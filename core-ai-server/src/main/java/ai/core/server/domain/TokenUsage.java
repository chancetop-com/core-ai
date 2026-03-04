package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class TokenUsage {
    @Field(name = "input")
    public Long input;

    @Field(name = "output")
    public Long output;
}
