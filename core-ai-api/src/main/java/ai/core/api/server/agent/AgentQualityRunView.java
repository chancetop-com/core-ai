package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Compact per-agent quality run summary. The full issue list stays behind the report endpoint.
 *
 * @author core-ai
 */
public class AgentQualityRunView {
    @Property(name = "run_id")
    public String runId;

    @Property(name = "mode")
    public String mode;

    @Property(name = "run_status")
    public String runStatus;

    @Property(name = "gate_result")
    public String gateResult;

    @Property(name = "policy_revision")
    public Integer policyRevision;

    @Property(name = "definition_revision")
    public Integer definitionRevision;

    @Property(name = "checks_complete")
    public Boolean checksComplete;

    @Property(name = "exempted")
    public Boolean exempted;

    @Property(name = "exemption_reason")
    public String exemptionReason;

    @Property(name = "warn_count")
    public Integer warnCount;

    @Property(name = "block_fail_count")
    public Integer blockFailCount;

    @Property(name = "incomplete_count")
    public Integer incompleteCount;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "finished_at")
    public ZonedDateTime finishedAt;
}
