package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Semantic attribution: links a raw record (session / run / workflow run / file) to a subject WITHOUT
 * writing anything back onto the raw record. Conversations and runs may carry several rows (one chat
 * can cover several subjects); a FILE has exactly one home per project (unique partial index on
 * project_id + target_id for target_type=file), so a report never shows up under two subjects.
 * <p>
 * Rows are written by the attributor LLM, by deterministic binding (schedule → run → artifact
 * inheritance, cascade from an attributed parent), by CLI/UI upload and by manual moves; {@code source}
 * records which.
 *
 * @author stephen
 */
@Collection(name = "project_subject_attributions")
public class ProjectSubjectAttribution {
    @Id
    public String id;

    // denormalized from the subject so project-level queries and the file uniqueness rule need no join
    @Field(name = "project_id")
    public String projectId;

    @Field(name = "subject_id")
    public String subjectId;

    // attributor | schedule | inherited | cascade | manual | upload (null on legacy rows = attributor)
    @Field(name = "source")
    public String source;

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
