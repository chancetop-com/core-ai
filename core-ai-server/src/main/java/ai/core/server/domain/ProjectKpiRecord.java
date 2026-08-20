package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Embedded KPI snapshot (product decision D4: free key, append-only series per subject+key).
 *
 * @author stephen
 */
public class ProjectKpiRecord {
    @Field(name = "subject_id")
    public String subjectId;   // required when the project has subjects (D5)

    @Field(name = "key")
    public String key;

    @Field(name = "value")
    public String value;   // numeric or text, stored as String; chart layer parses numerics

    @Field(name = "unit")
    public String unit;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "created_by")
    public String createdBy;   // agentId
}
