package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GraderReportView {
    @Property(name = "grader_id")
    public String graderId;

    @Property(name = "required")
    public Boolean required;

    @Property(name = "applicable")
    public Integer applicable;

    @Property(name = "completed")
    public Integer completed;

    @Property(name = "pass")
    public Integer pass;

    @Property(name = "fail")
    public Integer fail;

    @Property(name = "indeterminate")
    public Integer indeterminate;

    @Property(name = "error")
    public Integer error;

    @Property(name = "not_run_target_failed")
    public Integer notRunTargetFailed;

    @Property(name = "pass_rate")
    public Double passRate;
}
