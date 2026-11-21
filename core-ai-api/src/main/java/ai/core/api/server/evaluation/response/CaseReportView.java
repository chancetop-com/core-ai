package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CaseReportView {
    @Property(name = "case_id")
    public String caseId;

    @Property(name = "split")
    public String split;

    @Property(name = "weight")
    public Double weight;

    @Property(name = "success")
    public Integer success;

    @Property(name = "failure")
    public Integer failure;

    @Property(name = "unmeasured")
    public Integer unmeasured;

    @Property(name = "success_fraction")
    public Double successFraction;

    @Property(name = "any_success")
    public Boolean anySuccess;

    @Property(name = "all_success")
    public Boolean allSuccess;

    @Property(name = "trials")
    public List<TrialSummaryView> trials;
}
