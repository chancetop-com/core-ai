package ai.core.server.hub;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgHubCallRow {
    @Column(name = "id")
    public String id;

    @Column(name = "kind")
    public String kind;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "user_type")
    public String userType;

    @Column(name = "source")
    public String source;

    @Column(name = "target")
    public String target;

    @Column(name = "ref_id")
    public String refId;

    @Column(name = "group_name")
    public String group;

    @Column(name = "name")
    public String name;

    @Column(name = "sandbox_id")
    public String sandboxId;

    @Column(name = "tool_kind")
    public String toolKind;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "args_hash")
    public String argsHash;

    @Column(name = "args_preview")
    public String argsPreview;

    @Column(name = "success")
    public Boolean success;

    @Column(name = "is_error")
    public Boolean isError;

    @Column(name = "status_code")
    public Integer statusCode;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "output_bytes")
    public Integer outputBytes;

    @Column(name = "error_message")
    public String errorMessage;

    @Column(name = "task_id")
    public String taskId;

    @Column(name = "context_id")
    public String contextId;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "data")
    public String data;
}
