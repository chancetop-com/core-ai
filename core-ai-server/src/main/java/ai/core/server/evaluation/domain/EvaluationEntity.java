package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvaluationEntity {
    public String id;
    public String ownerId;
    public String agentId;
    public String name;
    public String description;
    public String defaultsJson;
    public String baselineRunId;
    public Integer baselineGeneration;
    public ZonedDateTime archivedAt;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
}
