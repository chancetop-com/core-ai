package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author core-ai
 */
@Collection(name = "session_feedback")
public class SessionFeedback {
    @Id
    public String id;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "agent_id")
    public String agentId;

    // === Layer 1: Outcome ===
    // COMPLETED | PARTIAL | FAILED
    @Field(name = "outcome")
    public String outcome;

    // === Layer 2: Failure Reasons (taxonomy) ===
    // UNDERSTANDING | PLANNING | EXECUTION | TOOL_USAGE | OUTPUT | EFFICIENCY | COLLABORATION | RELIABILITY | OTHER
    @Field(name = "failure_reasons")
    public List<String> failureReasons;

    @Field(name = "failure_detail")
    public String failureDetail;

    // === Layer 3: Agent Capability Ratings (1-5) ===
    @Field(name = "understanding_rating")
    public Integer understandingRating;

    @Field(name = "problem_solving_rating")
    public Integer problemSolvingRating;

    @Field(name = "tool_usage_rating")
    public Integer toolUsageRating;

    @Field(name = "communication_rating")
    public Integer communicationRating;

    @Field(name = "outcome_rating")
    public Integer outcomeRating;

    // === Layer 4: Agent Work-style Fit ===
    // TOO_ACTIVE | JUST_RIGHT | TOO_CONSERVATIVE
    @Field(name = "proactivity_fit")
    public String proactivityFit;

    // SHOULD_DECIDE | SHOULD_ASK | JUST_RIGHT
    @Field(name = "decision_fit")
    public String decisionFit;

    // === Layer 5: Trust ===
    // FULLY_TRUST | MOSTLY_TRUST | NEED_CONFIRM | WOULD_NOT_USE
    @Field(name = "trust_level")
    public String trustLevel;

    // === Free Text ===
    @Field(name = "comment")
    public String comment;

    // === Auto-collected Session Data ===
    @Field(name = "model_id")
    public String modelId;

    @Field(name = "token_count")
    public Long tokenCount;

    @Field(name = "session_duration_ms")
    public Long sessionDurationMs;

    @Field(name = "tool_call_count")
    public Integer toolCallCount;

    @Field(name = "tool_error_count")
    public Integer toolErrorCount;

    @Field(name = "message_count")
    public Integer messageCount;

    @Field(name = "source")
    public String source;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
