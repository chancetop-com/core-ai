package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class TrialSummaryView {
    @Property(name = "repetition_index")
    public Integer repetitionIndex;

    @Property(name = "execution_result")
    public String executionResult;

    @Property(name = "task_result")
    public String taskResult;

    @Property(name = "duration_ms")
    public Long durationMs;

    @Property(name = "input_tokens")
    public Integer inputTokens;

    @Property(name = "output_tokens")
    public Integer outputTokens;
}
