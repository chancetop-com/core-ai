package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class TrialDetailView {
    @Property(name = "id")
    public String id;

    @Property(name = "run_id")
    public String runId;

    @Property(name = "case_id")
    public String caseId;

    @Property(name = "repetition_index")
    public Integer repetitionIndex;

    @Property(name = "split")
    public String split;

    @Property(name = "weight")
    public Double weight;

    @Property(name = "status")
    public String status;

    @Property(name = "execution_result")
    public String executionResult;

    @Property(name = "task_result")
    public String taskResult;

    @Property(name = "agent_run_id")
    public String agentRunId;

    @Property(name = "actual_output")
    public String actualOutput;

    @Property(name = "grading_json")
    public String gradingJson;

    @Property(name = "usage_json")
    public String usageJson;

    @Property(name = "error")
    public String error;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;

    @Property(name = "case_input_json")
    public String caseInputJson;

    @Property(name = "case_reference_json")
    public String caseReferenceJson;

    @Property(name = "trace_json")
    public String traceJson;

    @Property(name = "artifacts_json")
    public String artifactsJson;
}
