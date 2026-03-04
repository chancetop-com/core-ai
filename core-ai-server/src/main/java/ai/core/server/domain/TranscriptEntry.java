package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class TranscriptEntry {
    @Field(name = "ts")
    public ZonedDateTime timestamp;

    @Field(name = "role")
    public String role;

    @Field(name = "content")
    public String content;

    @Field(name = "name")
    public String name;

    @Field(name = "args")
    public String args;

    @Field(name = "status")
    public String status;

    @Field(name = "result")
    public String result;
}
