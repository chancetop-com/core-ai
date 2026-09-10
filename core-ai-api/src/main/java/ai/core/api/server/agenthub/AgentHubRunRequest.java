package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * One {@code run} call. {@code contextId} continues an existing conversation (the server
 * session created by the first run), {@code detach} returns as soon as the task is
 * submitted, {@code timeoutSeconds} bounds how long the call blocks (default 120, max 300 —
 * a timeout is not a failure, poll {@code status} instead).
 *
 * @author stephen
 */
public class AgentHubRunRequest {
    @Property(name = "task")
    public String task;

    @Property(name = "context_id")
    public String contextId;

    @Property(name = "attachments")
    public List<AgentHubAttachment> attachments;

    @Property(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Property(name = "detach")
    public Boolean detach;

    @Property(name = "max_output_chars")
    public Integer maxOutputChars;
}
