package ai.core.server.comparison;

import ai.core.server.comparison.domain.ComparisonStatus;

/**
 * @author stephen
 */
public class AgentComparisonQuery {
    public String agentId;
    public String createdBy;
    public String participantUserId;
    public ComparisonStatus status;
    public int limit = 100;
}
