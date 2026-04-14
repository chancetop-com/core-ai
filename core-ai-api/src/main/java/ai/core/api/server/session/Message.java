package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class Message {
    @Property(name = "role")
    public String role;

    @Property(name = "content")
    public String content;

    @Property(name = "thinking")
    public String thinking;

    @Property(name = "tools")
    public List<ToolCallRecord> tools;

    @Property(name = "seq")
    public Long seq;

    @Property(name = "trace_id")
    public String traceId;

    @Property(name = "timestamp")
    public Instant timestamp;

    @Property(name = "metadata")
    public Map<String, String> metadata;

    public static class ToolCallRecord {
        @Property(name = "call_id")
        public String callId;

        @Property(name = "name")
        public String name;

        @Property(name = "arguments")
        public String arguments;

        @Property(name = "result")
        public String result;

        @Property(name = "status")
        public String status;
    }
}
