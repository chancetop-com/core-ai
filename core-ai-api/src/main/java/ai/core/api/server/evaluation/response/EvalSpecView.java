package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvalSpecView {
    @Property(name = "id")
    public String id;

    @Property(name = "revision")
    public Integer revision;

    @Property(name = "dataset_revision_id")
    public String datasetRevisionId;

    @Property(name = "dataset_fingerprint")
    public String datasetFingerprint;

    @Property(name = "grader_suite_id")
    public String graderSuiteId;

    @Property(name = "fingerprint")
    public String fingerprint;

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

    @Property(name = "default_clock")
    public String defaultClock;

    @Property(name = "split")
    public String split;

    @Property(name = "case_count")
    public Integer caseCount;

    @Property(name = "planned_trials")
    public Integer plannedTrials;

    @Property(name = "body_json")
    public String bodyJson;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
