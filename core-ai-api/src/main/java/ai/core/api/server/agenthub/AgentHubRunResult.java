package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Result of one hub run, whatever the agent type.
 * <p>
 * {@code status} is one of {@code completed} / {@code input_required} / {@code running} /
 * {@code failed} / {@code cancelled}. {@code inputRequest} is only set while
 * {@code input_required} (reply with the {@code reply} endpoint), {@code output} is truncated
 * to {@code max_output_chars} with {@code outputTruncated} flagging it, and {@code tokenUsage}
 * is only known once the turn finished.
 *
 * @author stephen
 */
public class AgentHubRunResult {
    @Property(name = "task_id")
    public String taskId;

    @Property(name = "context_id")
    public String contextId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "status")
    public String status;

    @Property(name = "output")
    public String output;

    @Property(name = "output_truncated")
    public Boolean outputTruncated;

    @Property(name = "input_request")
    public AgentHubInputRequest inputRequest;

    @Property(name = "token_usage")
    public Map<String, Long> tokenUsage;

    @Property(name = "duration_ms")
    public Long durationMs;

    @Property(name = "error_code")
    public String errorCode;

    @Property(name = "error_message")
    public String errorMessage;
}
