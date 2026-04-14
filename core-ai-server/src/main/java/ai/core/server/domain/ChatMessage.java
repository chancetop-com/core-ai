package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author Xander
 */
@Collection(name = "chat_messages")
public class ChatMessage {
    @Id
    public String id;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "seq")
    public Long seq;

    // user | agent
    @Field(name = "role")
    public String role;

    @Field(name = "content")
    public String content;

    @Field(name = "thinking")
    public String thinking;

    @Field(name = "tools")
    public List<ToolCallRecord> tools;

    // optional pointer for debug jump, not indexed
    @Field(name = "trace_id")
    public String traceId;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    public static class ToolCallRecord {
        @Field(name = "call_id")
        public String callId;

        @Field(name = "name")
        public String name;

        @Field(name = "arguments")
        public String arguments;

        @Field(name = "result")
        public String result;

        @Field(name = "status")
        public String status;
    }
}
