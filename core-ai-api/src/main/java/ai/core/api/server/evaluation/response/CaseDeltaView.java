package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CaseDeltaView {
    @Property(name = "case_id")
    public String caseId;

    @Property(name = "baseline_success_fraction")
    public Double baselineSuccessFraction;

    @Property(name = "candidate_success_fraction")
    public Double candidateSuccessFraction;

    @Property(name = "delta")
    public Double delta;
}
