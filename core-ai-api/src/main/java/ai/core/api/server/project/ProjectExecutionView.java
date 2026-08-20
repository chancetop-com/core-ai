package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * One execution row (chat session / agent run / workflow run) in the project executions tab.
 *
 * @author stephen
 */
public class ProjectExecutionView {
    @Property(name = "id")
    public String id;

    @Property(name = "type")
    public String type;   // chat | run | workflow

    @Property(name = "title")
    public String title;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "status")
    public String status;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @Property(name = "trace_id")
    public String traceId;

    @Property(name = "subject_id")
    public String subjectId;
}
