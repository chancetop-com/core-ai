package ai.core.memory.history;

import ai.core.llm.domain.RoleType;

import java.time.Instant;

/**
 * @author xander
 */
public record ChatRecord(
    RoleType role,
    String content,
    Instant timestamp
) {
    public static ChatRecord user(String content, Instant timestamp) {
        return new ChatRecord(RoleType.USER, content, timestamp);
    }

    public static ChatRecord assistant(String content, Instant timestamp) {
        return new ChatRecord(RoleType.ASSISTANT, content, timestamp);
    }

    public static ChatRecord tool(String content, Instant timestamp) {
        return new ChatRecord(RoleType.TOOL, content, timestamp);
    }
}
