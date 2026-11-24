package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Latest completed author-side quality run (PRECHECK/PUBLISH/FORK), not the publish receipt.
 * A failed run stays visible until a later run passes; contentChanged/policyChanged tell the
 * editor whether the result still describes the current input.
 *
 * @author core-ai
 */
public class AgentQualityStatusView {
    @Property(name = "state")
    public String state;

    @Property(name = "run_id")
    public String runId;

    @Property(name = "mode")
    public String mode;

    @Property(name = "gate_result")
    public String gateResult;

    @Property(name = "block_fail_count")
    public Integer blockFailCount;

    @Property(name = "incomplete_count")
    public Integer incompleteCount;

    @Property(name = "warn_count")
    public Integer warnCount;

    @Property(name = "exempted")
    public Boolean exempted;

    @Property(name = "checked_at")
    public ZonedDateTime checkedAt;

    @Property(name = "definition_revision")
    public Integer definitionRevision;

    @Property(name = "current_revision")
    public Integer currentRevision;

    @Property(name = "policy_revision")
    public Integer policyRevision;

    @Property(name = "policy_changed")
    public Boolean policyChanged;

    @Property(name = "content_changed")
    public Boolean contentChanged;

    @Property(name = "message")
    public String message;
}
