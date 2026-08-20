package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Semantic attribution produced by the project agent analysis: links a raw record (session / run /
 * workflow run / file) to a subject WITHOUT writing anything back onto the raw record. Multiple rows
 * per target are allowed (one conversation can cover several subjects).
 *
 * @author stephen
 */
@Collection(name = "project_subject_attributions")
public class ProjectSubjectAttribution {
    @Id
    public String id;

    @Field(name = "subject_id")
    public String subjectId;

    // session | run | workflow_run | file
    @Field(name = "target_type")
    public String targetType;

    @Field(name = "target_id")
    public String targetId;

    // consumption marker: null = attributed but not yet analyzed; the subject-analysis run sets
    // this once the attributed material has been consumed (idempotent, per-attribution cursor)
    @Field(name = "analyzed_at")
    public ZonedDateTime analyzedAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
