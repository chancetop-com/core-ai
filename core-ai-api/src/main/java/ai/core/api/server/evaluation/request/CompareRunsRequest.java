package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CompareRunsRequest {
    @Property(name = "baseline_run_id")
    public String baselineRunId;

    @Property(name = "candidate_run_id")
    public String candidateRunId;
}
