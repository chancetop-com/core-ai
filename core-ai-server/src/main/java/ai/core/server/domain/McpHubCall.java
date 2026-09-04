package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Audit record of one MCP Hub tool execution. Full arguments/results are never stored
 * (they may contain sensitive data) — only a sha256 hash and a truncated preview.
 *
 * @author stephen
 */
@Collection(name = "mcp_hub_calls")
public class McpHubCall {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @Field(name = "user_type")
    public String userType;

    @Field(name = "source")
    public String source;

    @Field(name = "server_id")
    public String serverId;

    @Field(name = "server_name")
    public String serverName;

    @Field(name = "tool_name")
    public String toolName;

    @Field(name = "args_hash")
    public String argsHash;

    @Field(name = "args_preview")
    public String argsPreview;

    @Field(name = "success")
    public Boolean success;

    @Field(name = "is_error")
    public Boolean isError;

    @Field(name = "duration_ms")
    public Long durationMs;

    @Field(name = "output_bytes")
    public Integer outputBytes;

    @Field(name = "error_message")
    public String errorMessage;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
