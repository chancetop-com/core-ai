package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CreateSpecRequest {
    @Property(name = "dataset_id")
    public String datasetId;

    @Property(name = "grader_suite_id")
    public String graderSuiteId;

    @Property(name = "profile")
    public String profile;

    @Property(name = "repetitions")
    public Integer repetitions;

    @Property(name = "agent_concurrency")
    public Integer agentConcurrency;

    @Property(name = "trial_deadline_seconds")
    public Integer trialDeadlineSeconds;

    @Property(name = "trial_max_turns")
    public Integer trialMaxTurns;

    @Property(name = "run_deadline_seconds")
    public Integer runDeadlineSeconds;

    @Property(name = "split")
    public String split;

    @Property(name = "case_ids")
    public List<String> caseIds;

    @Property(name = "default_clock")
    public String defaultClock;
}
