package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class SubmitSessionFeedbackRequest {
    @Property(name = "outcome")
    public String outcome;

    @Property(name = "failure_reasons")
    public List<String> failureReasons;

    @Property(name = "failure_detail")
    public String failureDetail;

    @Property(name = "understanding_rating")
    public Integer understandingRating;

    @Property(name = "problem_solving_rating")
    public Integer problemSolvingRating;

    @Property(name = "tool_usage_rating")
    public Integer toolUsageRating;

    @Property(name = "communication_rating")
    public Integer communicationRating;

    @Property(name = "outcome_rating")
    public Integer outcomeRating;

    @Property(name = "proactivity_fit")
    public String proactivityFit;

    @Property(name = "decision_fit")
    public String decisionFit;

    @Property(name = "trust_level")
    public String trustLevel;

    @Property(name = "comment")
    public String comment;

    @Property(name = "model_id")
    public String modelId;

    @Property(name = "token_count")
    public Long tokenCount;

    @Property(name = "session_duration_ms")
    public Long sessionDurationMs;

    @Property(name = "tool_call_count")
    public Integer toolCallCount;

    @Property(name = "tool_error_count")
    public Integer toolErrorCount;

    @Property(name = "message_count")
    public Integer messageCount;

    @Property(name = "source")
    public String source;
}
