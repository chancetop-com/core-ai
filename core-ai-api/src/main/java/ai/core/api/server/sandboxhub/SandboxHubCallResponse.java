package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Outcome of one sandbox hub call, shape shared by the tool-call response and the task poll
 * ({@code sdk/core-ai-session/contract-fixtures/call-success.json}, {@code pending-call.json}).
 * <p>
 * Deliberately not {@code mcphub.HubCallResponse}: that DTO is the wire contract of the four
 * user-facing hubs (CLI {@code --json} output included), while this one carries the sandbox-only
 * {@code status}/{@code task_id}/{@code llm_usage} fields. Keeping them apart avoids changing the
 * user hubs' payloads.
 *
 * @author stephen
 */
public class SandboxHubCallResponse {
    @Property(name = "call_id")
    public String callId;

    /** {@code completed} / {@code pending} / {@code failed}. */
    @Property(name = "status")
    public String status;

    @Property(name = "success")
    public Boolean success;

    @Property(name = "is_error")
    public Boolean isError;

    @Property(name = "text")
    public String text;

    @Property(name = "content")
    public List<SandboxHubContentPart> content;

    @Property(name = "duration_ms")
    public Long durationMs;

    /** Set when the tool is async and still running; the caller polls {@code /tasks/:task_id}. */
    @Property(name = "task_id")
    public String taskId;

    @Property(name = "error_code")
    public String errorCode;

    @Property(name = "error_message")
    public String errorMessage;

    /** Upstream HTTP status for api tools (business failures still return hub HTTP 200). */
    @Property(name = "status_code")
    public Integer statusCode;

    @Property(name = "llm_usage")
    public SandboxHubLlmUsage llmUsage;
}
