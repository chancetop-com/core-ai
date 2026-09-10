package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Audit record of one Hub tool execution (kind: {@code mcp_tool} / {@code api_tool} /
 * {@code agent}). Full arguments/results are never stored (they may contain sensitive
 * data) — only a sha256 hash and a truncated preview. {@code target} is the qualified
 * name ({@code server/tool} or {@code app/service/operation}), {@code group} the owning
 * server/app and {@code name} the executed tool/operation name.
 * <p>
 * An agent run additionally records the A2A {@code task_id} of the turn, the {@code context_id}
 * that continues the conversation, and the token usage of the turn when it finished in-band.
 *
 * @author stephen
 */
@Collection(name = "hub_calls")
public class HubCall {
    @Id
    public String id;

    @NotNull
    @Field(name = "kind")
    public String kind;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @Field(name = "user_type")
    public String userType;

    @Field(name = "source")
    public String source;

    @Field(name = "target")
    public String target;

    @Field(name = "ref_id")
    public String refId;

    @Field(name = "group")
    public String group;

    @Field(name = "name")
    public String name;

    @Field(name = "args_hash")
    public String argsHash;

    @Field(name = "args_preview")
    public String argsPreview;

    @Field(name = "success")
    public Boolean success;

    @Field(name = "is_error")
    public Boolean isError;

    @Field(name = "status_code")
    public Integer statusCode;

    @Field(name = "duration_ms")
    public Long durationMs;

    @Field(name = "output_bytes")
    public Integer outputBytes;

    @Field(name = "error_message")
    public String errorMessage;

    @Field(name = "task_id")
    public String taskId;

    @Field(name = "context_id")
    public String contextId;

    @Field(name = "input_tokens")
    public Long inputTokens;

    @Field(name = "output_tokens")
    public Long outputTokens;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
